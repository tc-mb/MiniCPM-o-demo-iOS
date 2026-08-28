package com.example.minicpm_v_demo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class PendingImageAttachment(
    val requestId: Long,
    val thumbnail: Bitmap,
    val imageInfo: String,
    val originalImageToken: String
)

sealed interface PendingImageUiState {
    data object Empty : PendingImageUiState
    data class LoadingPreview(val requestId: Long) : PendingImageUiState
    data class Preprocessing(
        val attachment: PendingImageAttachment
    ) : PendingImageUiState
    data class Ready(
        val attachment: PendingImageAttachment
    ) : PendingImageUiState
    data object Clearing : PendingImageUiState
}

sealed interface PendingImageEvent {
    data class Error(val message: String) : PendingImageEvent
}

class PendingImageViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val contentResolver = application.contentResolver
    private val engine by lazy { LlamaEngine.getInstance(appContext) }
    private val sourceCache = ImageSourceCache(
        File(appContext.filesDir, SOURCE_CACHE_DIRECTORY),
        ImageDecodePolicy.MAX_SOURCE_BYTES
    )
    private val stateLock = Any()

    private val _uiState = MutableStateFlow<PendingImageUiState>(
        PendingImageUiState.Empty
    )
    val uiState: StateFlow<PendingImageUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<PendingImageEvent>(Channel.BUFFERED)
    val events: Flow<PendingImageEvent> = eventChannel.receiveAsFlow()

    private var nextRequestId = 1L
    private var activeRequestId: Long? = null
    private var processingJob: Job? = null

    fun controls(
        modelReady: Boolean,
        engineBusy: Boolean,
        videoProcessing: Boolean,
        hasText: Boolean
    ): ChatInputControls {
        val state = _uiState.value
        if (
            !modelReady ||
            engineBusy ||
            videoProcessing ||
            state is PendingImageUiState.Clearing
        ) {
            return ChatInputControls(
                textEnabled = false,
                sendEnabled = false,
                mediaEnabled = false,
                modelSettingsEnabled = false
            )
        }

        val isPreprocessing =
            state is PendingImageUiState.LoadingPreview ||
                state is PendingImageUiState.Preprocessing
        val hasPendingImage = isPreprocessing || state is PendingImageUiState.Ready
        return ChatInputControls(
            textEnabled = true,
            sendEnabled = hasText && !isPreprocessing,
            mediaEnabled = !hasPendingImage,
            modelSettingsEnabled = !hasPendingImage
        )
    }

    /**
     * Starts preprocessing and assumes ownership of [cameraCacheFile], if supplied.
     * The camera file is deleted after all decoder streams have closed, including
     * cancellation and failure paths.
     */
    fun start(uri: Uri, cameraCacheFile: File? = null): Boolean {
        val request = synchronized(stateLock) {
            if (
                processingJob != null ||
                _uiState.value !is PendingImageUiState.Empty
            ) {
                null
            } else {
                val requestId = nextRequestId++
                activeRequestId = requestId
                _uiState.value = PendingImageUiState.LoadingPreview(requestId)
                val job = viewModelScope.launch(
                    context = Dispatchers.IO,
                    start = CoroutineStart.LAZY
                ) {
                    preprocess(requestId, uri, cameraCacheFile)
                }
                processingJob = job
                requestId to job
            }
        }
        if (request == null) {
            deleteCameraCacheFile(cameraCacheFile)
            return false
        }

        val (_, job) = request
        job.start()
        return true
    }

    fun consumeReady(): PendingImageAttachment? =
        synchronized(stateLock) {
            val ready = _uiState.value as? PendingImageUiState.Ready
                ?: return@synchronized null
            activeRequestId = null
            processingJob = null
            _uiState.value = PendingImageUiState.Empty
            ready.attachment
        }

    /** Rebuilds visual context from an app-private opaque source token. */
    suspend fun replayCachedImage(originalImageToken: String) {
        val source = sourceCache.resolve(originalImageToken)
            ?: throw ImageSourceUnreadableException()
        var bitmap: Bitmap? = null
        var encodedFile: File? = null
        try {
            val metadata = readMetadata(source)
            bitmap = decodeOrientedBitmap(
                source = source,
                metadata = metadata,
                maxDimension = ImageDecodePolicy.MAX_DIMENSION,
                maxPixelCount = ImageDecodePolicy.MAX_PIXEL_COUNT
            )
            encodedFile = encodeToPrivateCache(bitmap)
            val encodedSize = encodedFile.length()
            if (!ImageDecodePolicy.isSourceLengthAllowed(encodedSize)) {
                throw ImageSourceTooLargeException()
            }
            bitmap.recycle()
            bitmap = null
            engine.prefillImage(encodedFile.readBytes())
        } finally {
            bitmap?.takeUnless { it.isRecycled }?.recycle()
            encodedFile?.let(::deletePreparedCacheFile)
        }
    }

    /**
     * Cancels and joins preprocessing before returning. [PendingImageCancellationMode.USER_REMOVE]
     * hides the attachment before waiting, while context resets keep a clearing indicator visible.
     * Callers can safely invoke LlamaEngine.clearContext()/unloadModel() afterwards without racing
     * a native image prefill that was already in flight.
     */
    suspend fun cancelAndClear(
        mode: PendingImageCancellationMode = PendingImageCancellationMode.CONTEXT_RESET
    ) {
        var retainedToken: String? = null
        val job = synchronized(stateLock) {
            activeRequestId = null
            val current = processingJob
            if (current == null) {
                retainedToken = currentAttachmentToken()
            }
            _uiState.value = when (
                PendingImageCancellationPolicy.displayWhileCancelling(
                    hasProcessingJob = current != null,
                    mode = mode
                )
            ) {
                PendingImageCancellationDisplay.HIDDEN -> PendingImageUiState.Empty
                PendingImageCancellationDisplay.CLEARING -> PendingImageUiState.Clearing
            }
            current
        }

        job?.cancelAndJoin()
        sourceCache.deleteToken(retainedToken)

        synchronized(stateLock) {
            if (processingJob === job) {
                processingJob = null
            }
            activeRequestId = null
            _uiState.value = PendingImageUiState.Empty
        }
    }

    /**
     * Synchronous local reset for a caller that has already reset or unloaded the
     * engine. It must not be used as a replacement for [cancelAndClear] while a
     * native prefill can still be running.
     */
    fun clearLocalAfterEngineReset() {
        var retainedToken: String? = null
        synchronized(stateLock) {
            activeRequestId = null
            if (processingJob == null) {
                retainedToken = currentAttachmentToken()
            }
            processingJob?.cancel()
            processingJob = null
            _uiState.value = PendingImageUiState.Empty
        }
        sourceCache.deleteToken(retainedToken)
    }

    private suspend fun preprocess(
        requestId: Long,
        uri: Uri,
        cameraCacheFile: File?
    ) {
        var modelBitmap: Bitmap? = null
        var cachedSource: CachedImageSource? = null
        var encodedFile: File? = null
        var retainSourceForViewer = false
        try {
            cachedSource = sourceCache.cache {
                contentResolver.openInputStream(uri)
            }
            val metadata = readMetadata(cachedSource.file)
            ensureCurrent(requestId)

            val thumbnail = decodeOrientedBitmap(
                source = cachedSource.file,
                metadata = metadata,
                maxDimension = THUMBNAIL_MAX_DIMENSION,
                maxPixelCount = THUMBNAIL_MAX_PIXEL_COUNT
            )
            ensureCurrent(requestId)

            val displayWidth = if (metadata.transform.rotationDegrees % 180 == 0) {
                metadata.width
            } else {
                metadata.height
            }
            val displayHeight = if (metadata.transform.rotationDegrees % 180 == 0) {
                metadata.height
            } else {
                metadata.width
            }
            val previewAttachment = PendingImageAttachment(
                requestId = requestId,
                thumbnail = thumbnail,
                imageInfo = "$displayWidth x $displayHeight",
                originalImageToken = cachedSource.token
            )
            publishIfCurrent(
                requestId,
                PendingImageUiState.Preprocessing(previewAttachment)
            )

            modelBitmap = decodeOrientedBitmap(
                source = cachedSource.file,
                metadata = metadata,
                maxDimension = ImageDecodePolicy.MAX_DIMENSION,
                maxPixelCount = ImageDecodePolicy.MAX_PIXEL_COUNT
            )
            ensureCurrent(requestId)
            check(ImageDecodePolicy.isPixelCountAllowed(
                width = modelBitmap.width,
                height = modelBitmap.height
            )) {
                appContext.getString(R.string.error_image_too_large)
            }

            encodedFile = encodeToPrivateCache(modelBitmap)
            val encodedSize = encodedFile.length()
            if (!ImageDecodePolicy.isSourceLengthAllowed(encodedSize)) {
                val errorResource = if (
                    encodedSize > ImageDecodePolicy.MAX_SOURCE_BYTES
                ) {
                    R.string.error_image_too_large
                } else {
                    R.string.error_decode_image
                }
                throw IOException(appContext.getString(errorResource))
            }
            val preparedAttachment = previewAttachment.copy(
                imageInfo = "${modelBitmap.width} x ${modelBitmap.height} " +
                    "(${encodedSize / 1024} KB)"
            )
            publishIfCurrent(
                requestId,
                PendingImageUiState.Preprocessing(preparedAttachment)
            )

            modelBitmap.recycle()
            modelBitmap = null
            val encodedBytes = encodedFile.readBytes()
            ensureCurrent(requestId)
            engine.prefillImage(encodedBytes)
            ensureCurrent(requestId)

            synchronized(stateLock) {
                if (activeRequestId == requestId) {
                    retainSourceForViewer = true
                    processingJob = null
                    _uiState.value = PendingImageUiState.Ready(preparedAttachment)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ImageSourceTooLargeException) {
            failRequest(
                requestId,
                appContext.getString(R.string.error_image_too_large)
            )
        } catch (_: ImageSourceUnreadableException) {
            failRequest(
                requestId,
                appContext.getString(R.string.error_read_image)
            )
        } catch (_: OutOfMemoryError) {
            failRequest(
                requestId,
                appContext.getString(R.string.error_decode_image)
            )
        } catch (error: Exception) {
            failRequest(
                requestId,
                error.localizedMessage
                    ?: appContext.getString(R.string.error_decode_image)
            )
        } finally {
            modelBitmap?.takeUnless { it.isRecycled }?.recycle()
            encodedFile?.let(::deletePreparedCacheFile)
            if (!retainSourceForViewer) {
                sourceCache.delete(cachedSource?.file)
            }
            deleteCameraCacheFile(cameraCacheFile)
        }
    }

    private fun readMetadata(source: File): ImageMetadata {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(source).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException(appContext.getString(R.string.error_decode_image))
        }

        val orientation = try {
            FileInputStream(source).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return ImageMetadata(
            width = bounds.outWidth,
            height = bounds.outHeight,
            transform = ExifOrientationPolicy.transformFor(orientation)
        )
    }

    private fun decodeOrientedBitmap(
        source: File,
        metadata: ImageMetadata,
        maxDimension: Int,
        maxPixelCount: Long
    ): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageDecodePolicy.sampleSizeFor(
                width = metadata.width,
                height = metadata.height,
                maxDimension = maxDimension,
                maxPixelCount = maxPixelCount
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = FileInputStream(source).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IOException(appContext.getString(R.string.error_decode_image))
        return applyExifTransform(decoded, metadata.transform)
    }

    private fun applyExifTransform(
        bitmap: Bitmap,
        transform: ExifOrientationTransform
    ): Bitmap {
        if (
            transform.rotationDegrees == 0 &&
            !transform.mirrorHorizontal
        ) {
            return bitmap
        }

        val matrix = Matrix().apply {
            if (transform.rotationDegrees != 0) {
                postRotate(transform.rotationDegrees.toFloat())
            }
            if (transform.mirrorHorizontal) {
                postScale(-1f, 1f)
            }
        }
        return try {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            ).also { transformed ->
                if (transformed !== bitmap) {
                    bitmap.recycle()
                }
            }
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
    }

    private fun encodeToPrivateCache(bitmap: Bitmap): File {
        val cacheDirectory = File(
            appContext.cacheDir,
            PREPARED_CACHE_DIRECTORY
        )
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException(appContext.getString(R.string.error_decode_image))
        }
        val canonicalDirectory = cacheDirectory.canonicalFile
        val format = if (bitmap.hasAlpha()) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val suffix = if (format == Bitmap.CompressFormat.PNG) ".png" else ".jpg"
        val outputFile = File.createTempFile(
            PREPARED_FILE_PREFIX,
            suffix,
            canonicalDirectory
        )
        check(outputFile.canonicalFile.parentFile == canonicalDirectory) {
            appContext.getString(R.string.error_decode_image)
        }

        try {
            FileOutputStream(outputFile).use { output ->
                if (!bitmap.compress(format, JPEG_QUALITY, output)) {
                    throw IOException(appContext.getString(R.string.error_decode_image))
                }
            }
            return outputFile
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        }
    }

    private fun deletePreparedCacheFile(file: File) {
        try {
            val cacheDirectory = File(
                appContext.cacheDir,
                PREPARED_CACHE_DIRECTORY
            ).canonicalFile
            val target = file.canonicalFile
            if (target.parentFile == cacheDirectory && target.isFile) {
                target.delete()
            }
        } catch (_: IOException) {
            // Best-effort cleanup in the app-private cache.
        }
    }

    private fun deleteCameraCacheFile(file: File?) {
        if (file == null) return
        try {
            val cameraDirectory = File(
                appContext.cacheDir,
                CAMERA_CACHE_DIRECTORY
            ).canonicalFile
            val target = file.canonicalFile
            if (target.parentFile == cameraDirectory && target.isFile) {
                target.delete()
            }
        } catch (_: IOException) {
            // Best-effort cleanup; never delete outside cache/camera.
        }
    }

    private fun failRequest(requestId: Long, message: String) {
        synchronized(stateLock) {
            if (activeRequestId == requestId) {
                activeRequestId = null
                processingJob = null
                _uiState.value = PendingImageUiState.Empty
                eventChannel.trySend(PendingImageEvent.Error(message))
            }
        }
    }

    private suspend fun ensureCurrent(requestId: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(requestId)) {
            throw CancellationException("Pending image request was replaced")
        }
    }

    private fun publishIfCurrent(
        requestId: Long,
        state: PendingImageUiState
    ) {
        synchronized(stateLock) {
            if (activeRequestId == requestId) {
                _uiState.value = state
            }
        }
    }

    private fun isCurrent(requestId: Long): Boolean =
        synchronized(stateLock) {
            activeRequestId == requestId
        }

    private fun currentAttachmentToken(): String? =
        when (val state = _uiState.value) {
            is PendingImageUiState.Preprocessing ->
                state.attachment.originalImageToken
            is PendingImageUiState.Ready ->
                state.attachment.originalImageToken
            else -> null
        }

    override fun onCleared() {
        val token = synchronized(stateLock) { currentAttachmentToken() }
        sourceCache.deleteToken(token)
        super.onCleared()
    }

    private data class ImageMetadata(
        val width: Int,
        val height: Int,
        val transform: ExifOrientationTransform
    )

    companion object {
        const val THUMBNAIL_MAX_DIMENSION = 512
        const val THUMBNAIL_MAX_PIXEL_COUNT = 512L * 512L

        private const val PREPARED_CACHE_DIRECTORY = "pending-images"
        const val SOURCE_CACHE_DIRECTORY = "conversation-images"
        private const val PREPARED_FILE_PREFIX = "prepared-"
        private const val CAMERA_CACHE_DIRECTORY = "camera"
        private const val JPEG_QUALITY = 95
    }
}
