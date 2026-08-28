package com.example.minicpm_v_demo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.example.minicpm_v_demo.rag.RagTurnPlan
import com.example.minicpm_v_demo.rag.RagPlanningStage
import com.example.minicpm_v_demo.rag.plainModelPromptOrNull
import com.example.minicpm_v_demo.rag.retrieval.CitationValidator
import com.example.minicpm_v_demo.rag.retrieval.RagVisualGroundingPolicy
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import com.example.minicpm_v_demo.rag.RagTurnTransaction
import com.example.minicpm_v_demo.rag.RagPromptTokenCounter
import com.example.minicpm_v_demo.rag.guard.CurrentGroundednessCalibration
import com.example.minicpm_v_demo.rag.guard.GroundednessClassifier
import com.example.minicpm_v_demo.rag.guard.RagReviewedGenerator
import com.example.minicpm_v_demo.rag.guard.ReviewedRagGeneration
import com.example.minicpm_v_demo.rag.guard.WatchdogGroundednessClassifier
import com.example.minicpm_v_demo.rag.ui.CitationSourceResolution
import com.example.minicpm_v_demo.rag.ui.CitationSourceResolver
import com.example.minicpm_v_demo.rag.telemetry.RagLatencyLogFormatter
import com.example.minicpm_v_demo.rag.telemetry.RagLatencyTrace
import com.example.minicpm_v_demo.rag.telemetry.RagPhase
import com.example.minicpm_v_demo.rag.telemetry.RagTraceResult
import io.noties.markwon.Markwon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

private sealed interface PendingPrivacyAction {
    data class SubmitPrompt(val prompt: String, val messageId: Long) : PendingPrivacyAction
    data class RevealResponse(val response: String) : PendingPrivacyAction
}

private data class ChatViewportAnchor(
    val adapterPosition: Int,
    val distanceFromContentBottomToItemTop: Int,
)

class MainActivity : StatusBarVisibleActivity() {

    private val pendingImageViewModel: PendingImageViewModel by viewModels()

    private lateinit var recyclerChat: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var etInput: TextInputEditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnImage: ImageButton
    private lateinit var btnCamera: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var cardInputBar: View
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var tvTitle: TextView
    private lateinit var pendingImagePanel: View
    private lateinit var ivPendingImage: ImageView
    private lateinit var pendingImageScrim: View
    private lateinit var progressPendingImage: CircularProgressIndicator
    private lateinit var tvPendingImageStatus: TextView
    private lateinit var tvPendingImageInfo: TextView
    private lateinit var btnRemovePendingImage: ImageButton
    private var lastImeBottomInset = 0
    private var pendingImeDismissTap = false
    private var imeDismissDownX = 0f
    private var imeDismissDownY = 0f
    private var imeDismissDownTime = 0L
    private val imeDismissTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var pendingImeViewportAnchor: ChatViewportAnchor? = null

    private lateinit var engine: LlamaEngine
    private var generationJob: Job? = null
    private var localGuardJob: Job? = null
    private var videoProcessingJob: Job? = null
    private var isModelReady = false
    private var isProcessingVideo = false
    private var isSubmitting = false
    private var isClearing = false
    private var hasAutoLoaded = false
    private var loadedModelId: String? = null
    private val conversationArchiveStore by lazy {
        ConversationArchiveDiskStore(File(filesDir, CONVERSATION_STORE_DIRECTORY))
    }
    private val conversationWriterDelegate = lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "conversation-persistence").apply { isDaemon = true }
        }
    }
    private val conversationWriter by conversationWriterDelegate
    private val conversationStoreDelegate = lazy {
        ConversationStore { getString(R.string.new_conversation) }.also { store ->
            loadConversationArchive()?.let(store::restore)
        }
    }
    private val conversationStore by conversationStoreDelegate
    private val messages: MutableList<ChatMessage>
        get() = conversationStore.active.messages
    private var createdWithLocale: String? = null
    private var isLocaleRestart = false
    private var currentEngineState: LlamaState = LlamaState.Uninitialized
    private var pendingCameraUri: Uri? = null
    private var pendingCameraFile: File? = null
    private var pendingPrivacyAction: PendingPrivacyAction? = null
    private val originalImageCache by lazy {
        ImageSourceCache(
            File(filesDir, PendingImageViewModel.SOURCE_CACHE_DIRECTORY),
            ImageDecodePolicy.MAX_SOURCE_BYTES
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdWithLocale = LocaleManager.currentLanguage(this).tag
        restorePendingCameraCapture(savedInstanceState)

        // If the selected model is a TTS model, redirect to TtsActivity immediately.
        // The chat interface is only meaningful for LLM/VLM models.
        if (shouldRedirectToTts()) {
            startActivity(Intent(this, TtsActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Edge-to-edge: pad the root content for status/nav bars and the IME
        // so the bottom input bar follows the soft keyboard up. Without this,
        // targetSdk=35+ draws content behind the IME and the input bar gets
        // covered.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val rootContent = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootContent) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            lastImeBottomInset = ime.bottom
            v.updatePadding(
                left = sysBars.left,
                top = sysBars.top,
                right = sysBars.right,
                bottom = maxOf(sysBars.bottom, ime.bottom)
            )
            if (ime.bottom > 0 && ::recyclerChat.isInitialized &&
                pendingImeViewportAnchor != null
            ) {
                v.doOnLayout { restoreImeViewportAnchor() }
            } else if (ime.bottom == 0) {
                pendingImeViewportAnchor = null
            }
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            rootContent,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat = insets

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0 &&
                        lastImeBottomInset > 0 && ::recyclerChat.isInitialized &&
                        pendingImeViewportAnchor != null
                    ) {
                        rootContent.doOnLayout {
                            restoreImeViewportAnchor()
                            pendingImeViewportAnchor = null
                        }
                    }
                }
            },
        )

        LlamaEngine.migrateLegacyLayoutIfNeeded(applicationContext)

        initViews()
        setupRecyclerView()
        setupClickListeners()
        observePendingImage()
        initEngine()
    }

    private fun initViews() {
        recyclerChat = findViewById(R.id.recycler_chat)
        etInput = findViewById(R.id.et_input)
        btnSend = findViewById(R.id.btn_send)
        btnImage = findViewById(R.id.btn_image)
        btnCamera = findViewById(R.id.btn_camera)
        btnSettings = findViewById(R.id.btn_settings)
        cardInputBar = findViewById(R.id.card_input_bar)
        appBarLayout = findViewById(R.id.appBarLayout)
        tvTitle = findViewById(R.id.tv_title)
        pendingImagePanel = findViewById(R.id.pending_image_panel)
        ivPendingImage = findViewById(R.id.iv_pending_image)
        pendingImageScrim = findViewById(R.id.pending_image_scrim)
        progressPendingImage = findViewById(R.id.progress_pending_image)
        tvPendingImageStatus = findViewById(R.id.tv_pending_image_status)
        tvPendingImageInfo = findViewById(R.id.tv_pending_image_info)
        btnRemovePendingImage = findViewById(R.id.btn_remove_pending_image)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(Markwon.create(this))
        chatAdapter.setOnStopClick {
            if (localGuardJob?.isActive == true) {
                localGuardJob?.cancel()
            } else {
                engine.cancelGeneration()
            }
        }
        chatAdapter.setOnImageClick(::openOriginalImage)
        chatAdapter.setOnWelcomeAction(::handleWelcomeAction)
        chatAdapter.setOnPrivacyInputChoice(::handlePrivacyInputChoice)
        chatAdapter.setOnMessageLongClick(::showMessageActions)
        chatAdapter.setOnCitationClick(::showCitationDetails)

        recyclerChat.layoutManager = LinearLayoutManager(this)
        recyclerChat.adapter = chatAdapter
        recyclerChat.setPadding(
            recyclerChat.paddingLeft,
            recyclerChat.paddingTop,
            recyclerChat.paddingRight,
            resources.getDimensionPixelSize(R.dimen.chat_message_spacing),
        )

        val selectedModel = LlamaEngine.getSelectedModel(applicationContext)
        if (messages.isEmpty()) messages.add(createWelcomeMessage(selectedModel))
        restorePendingPrivacyInput()
        submitMessages()
    }

    private fun showCitationDetails(citation: CitationRef) {
        lifecycleScope.launch {
            val resolution = withContext(Dispatchers.IO) {
                runCatching {
                    val database = (application as MiniCPMApplication).ragDatabase
                    CitationSourceResolver.resolve(
                        citation = citation,
                        document = database.documentDao().findById(citation.documentId),
                        chunk = database.chunkDao().findByIds(listOf(citation.chunkId)).singleOrNull(),
                    )
                }.getOrElse {
                    CitationSourceResolution.Unavailable(
                        documentNameSnapshot = citation.documentNameSnapshot,
                        locator = citation.locator,
                        archivedExcerpt = citation.quotedText,
                    )
                }
            }
            val details = when (resolution) {
                is CitationSourceResolution.Available -> getString(
                    R.string.rag_source_available_body,
                    resolution.documentName,
                    resolution.locator,
                    resolution.indexedText,
                )
                is CitationSourceResolution.Deleted -> getString(
                    R.string.rag_source_deleted_body,
                    resolution.documentNameSnapshot,
                    resolution.locator,
                    resolution.archivedExcerpt,
                )
                is CitationSourceResolution.Unavailable -> getString(
                    R.string.rag_source_unavailable_body,
                    resolution.documentNameSnapshot,
                    resolution.locator,
                    resolution.archivedExcerpt,
                )
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.rag_source_detail_title, citation.sourceId))
                .setMessage(details)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }
    }

    private fun loadConversationArchive(): ConversationArchive? = try {
        conversationWriter.submit<ConversationArchive?> {
            conversationArchiveStore.load()?.let { archive ->
                val retainedTokens = archive.conversations.asSequence()
                    .flatMap { it.messages.asSequence() }
                    .filterIsInstance<ChatMessage.UserMessage>()
                    .flatMap { sequenceOf(it.originalImageToken, it.previewImageToken) }
                    .filterNotNull()
                    .toSet()
                originalImageCache.deleteUnreferencedTokens(retainedTokens)
                hydrateConversationArchive(archive)
            }
        }.get()
    } catch (error: Exception) {
        Log.e(TAG, "Could not load saved conversations", error)
        null
    }

    private fun hydrateConversationArchive(archive: ConversationArchive): ConversationArchive =
        archive.copy(
            conversations = archive.conversations.map { conversation ->
                conversation.copy(
                    messages = conversation.messages.mapNotNull { message ->
                        when (message) {
                            is ChatMessage.UserMessage -> {
                                val previewToken = message.previewImageToken
                                    ?: message.originalImageToken
                                message.copy(
                                    imageBitmap = StoredImageThumbnailLoader.load(
                                        originalImageCache,
                                        previewToken
                                    ),
                                    isPrefilling = false
                                )
                            }
                            is ChatMessage.AiMessage -> when {
                                message.isGenerating && message.text.isBlank() -> null
                                else -> message.copy(isGenerating = false)
                            }
                            is ChatMessage.WelcomeCard -> message
                        }
                    }.toMutableList()
                )
            }
        )

    private fun restorePendingPrivacyInput() {
        val pending = messages.lastOrNull() as? ChatMessage.UserMessage
        if (pending?.requiresPrivacyConfirmation == true) {
            pendingPrivacyAction = PendingPrivacyAction.SubmitPrompt(pending.text, pending.id)
            isSubmitting = true
        }
    }

    private fun submitMessages(commitCallback: (() -> Unit)? = null) {
        val snapshot = messages.toList()
        if (commitCallback == null) {
            chatAdapter.submitList(snapshot)
        } else {
            chatAdapter.submitList(snapshot, commitCallback)
        }
        persistConversations()
    }

    private fun persistConversations() {
        val archive = conversationStore.snapshot()
        try {
            conversationWriter.execute {
                try {
                    conversationArchiveStore.save(archive)
                } catch (error: Exception) {
                    Log.e(TAG, "Could not save conversations", error)
                }
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Conversation writer is unavailable", error)
        }
    }

    private fun cachePreview(bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, output)) {
                    throw IOException("Could not encode conversation thumbnail")
                }
                output.toByteArray()
            }
            originalImageCache.cache { ByteArrayInputStream(bytes) }.token
        } catch (error: Exception) {
            Log.w(TAG, "Could not persist conversation thumbnail", error)
            null
        }
    }

    private fun flushAndCloseConversationWriter() {
        val archive = conversationStore.snapshot()
        try {
            conversationWriter.submit {
                conversationArchiveStore.save(archive)
            }.get(CONVERSATION_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Exception) {
            Log.e(TAG, "Could not flush conversations", error)
        } finally {
            conversationWriter.shutdown()
        }
    }

    private fun handleWelcomeAction(action: WelcomeAction) {
        when (action) {
            is WelcomeAction.SendPrompt -> {
                if (isModelReady && !isProcessingVideo) {
                    etInput.setText(action.prompt)
                    handleUserInput()
                } else if (!isModelReady) {
                    Toast.makeText(
                        this,
                        R.string.toast_load_model_first,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, R.string.toast_wait_video, Toast.LENGTH_SHORT).show()
                }
            }
            WelcomeAction.PickMedia -> startVisualInput {
                getMedia.launch(arrayOf("image/*", "video/*"))
            }
            WelcomeAction.TakePhoto -> startVisualInput(::launchCameraCapture)
        }
    }

    private fun startVisualInput(action: () -> Unit) {
        when {
            !isModelReady || currentEngineState !is LlamaState.ModelReady ->
                Toast.makeText(this, R.string.toast_load_model_first, Toast.LENGTH_SHORT).show()
            isProcessingVideo ->
                Toast.makeText(this, R.string.toast_wait_video, Toast.LENGTH_SHORT).show()
            pendingImageViewModel.uiState.value !is PendingImageUiState.Empty ->
                Toast.makeText(
                    this,
                    R.string.toast_wait_image_preprocessing,
                    Toast.LENGTH_SHORT
                ).show()
            !engine.isVisionSupported ->
                Toast.makeText(this, R.string.toast_load_model_first, Toast.LENGTH_SHORT).show()
            else -> action()
        }
    }

    private fun setupClickListeners() {
        // Pick image OR video.  iOS demo's HXPhotoPicker exposes both
        // photo and video in a single picker; on Android we ask SAF
        // for either MIME, so the user gets the same "pick anything
        // viewable" affordance with no extra "video" button.  Video is
        // only fed to the model if the loaded model is V-4.6 (gated in
        // [handleSelectedMedia] / [LlamaEngine.isVideoUnderstandingSupported]).
        btnImage.setOnClickListener { getMedia.launch(arrayOf("image/*", "video/*")) }
        btnCamera.setOnClickListener { launchCameraCapture() }
        btnSend.setOnClickListener { handleUserInput() }
        btnSettings.setOnClickListener { showChatSettingsDialog() }
        ivPendingImage.setOnClickListener {
            val token = when (val state = pendingImageViewModel.uiState.value) {
                is PendingImageUiState.Preprocessing ->
                    state.attachment.originalImageToken
                is PendingImageUiState.Ready ->
                    state.attachment.originalImageToken
                else -> null
            }
            token?.let(::openOriginalImage)
        }
        btnRemovePendingImage.setOnClickListener { removePendingImage() }

        etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                captureImeViewportAnchor()
                collapseAppBar()
            }
        }
        etInput.doAfterTextChanged { refreshInputControls() }
    }

    private fun observePendingImage() {
        lifecycleScope.launch {
            pendingImageViewModel.uiState.collect { state ->
                renderPendingImage(state)
                refreshInputControls()
            }
        }
        lifecycleScope.launch {
            pendingImageViewModel.events.collect { event ->
                when (event) {
                    is PendingImageEvent.Error -> Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_image_failed, event.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun collapseAppBar() {
        appBarLayout.setExpanded(false, true)
    }

    private fun scrollToBottom() {
        recyclerChat.post {
            val adapterCount = chatAdapter.itemCount
            if (adapterCount == 0) return@post
            val layoutManager = recyclerChat.layoutManager as? LinearLayoutManager ?: return@post
            val lastView = layoutManager.findViewByPosition(adapterCount - 1)
            if (lastView != null) {
                val offset = recyclerChat.height - recyclerChat.paddingBottom - lastView.height
                layoutManager.scrollToPositionWithOffset(adapterCount - 1, offset.coerceAtMost(0))
            } else {
                recyclerChat.scrollToPosition(adapterCount - 1)
            }
        }
    }

    private fun captureImeViewportAnchor() {
        val layoutManager = recyclerChat.layoutManager as? LinearLayoutManager ?: return
        val adapterPosition = layoutManager.findLastVisibleItemPosition()
        if (adapterPosition == RecyclerView.NO_POSITION) return
        val anchorView = layoutManager.findViewByPosition(adapterPosition) ?: return
        val contentBottom = recyclerChat.height - recyclerChat.paddingBottom
        pendingImeViewportAnchor = ChatViewportAnchor(
            adapterPosition = adapterPosition,
            distanceFromContentBottomToItemTop = contentBottom - anchorView.top,
        )
    }

    private fun restoreImeViewportAnchor() {
        val anchor = pendingImeViewportAnchor ?: return
        if (anchor.adapterPosition !in 0 until chatAdapter.itemCount) return
        val layoutManager = recyclerChat.layoutManager as? LinearLayoutManager ?: return
        val contentBottom = recyclerChat.height - recyclerChat.paddingBottom
        layoutManager.scrollToPositionWithOffset(
            anchor.adapterPosition,
            contentBottom - anchor.distanceFromContentBottomToItemTop,
        )
    }

    private fun showClearChatDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_chat)
            .setMessage(R.string.clear_chat_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                clearChat()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showChatSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_chat_settings, null, false)
        val rowModelManagement = view.findViewById<View>(R.id.row_model_management)
        val rowKnowledgeBases = view.findViewById<View>(R.id.row_knowledge_bases)
        val rowConversationRag = view.findViewById<View>(R.id.row_conversation_rag)
        val rowImageSlice = view.findViewById<View>(R.id.row_image_slice)
        val rowConversationManagement = view.findViewById<View>(R.id.row_conversation_management)
        val rowClearChat = view.findViewById<View>(R.id.row_clear_chat)
        val selectedModel = LlamaEngine.getSelectedModel(applicationContext)

        view.findViewById<TextView>(R.id.tv_settings_model_summary).text =
            getString(R.string.settings_model_summary, selectedModel.displayName)
        view.findViewById<TextView>(R.id.tv_settings_slice_summary).text =
            getString(
                R.string.settings_slice_summary,
                LlamaEngine.getImageMaxSliceNums(this)
            )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.chat_settings)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val modelManagementEnabled = isModelManagerSafe()
        val imageSliceEnabled = canChangeImageSlices()
        val clearChatEnabled = canClearCurrentChat()
        rowImageSlice.visibility = if (
            ::engine.isInitialized && engine.isVisionSupported
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        rowModelManagement.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }
        rowKnowledgeBases.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, KnowledgeBaseActivity::class.java))
        }
        rowConversationRag.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, KnowledgeBaseActivity::class.java).apply {
                putExtra(KnowledgeBaseActivity.EXTRA_CONVERSATION_ID, conversationStore.activeConversationId)
            })
        }
        rowImageSlice.setOnClickListener {
            dialog.dismiss()
            showImageSliceDialog()
        }
        rowConversationManagement.setOnClickListener {
            dialog.dismiss()
            showConversationManagementDialog()
        }
        rowClearChat.setOnClickListener {
            dialog.dismiss()
            showClearChatDialog()
        }
        setSettingsRowEnabled(rowModelManagement, modelManagementEnabled)
        setSettingsRowEnabled(rowImageSlice, imageSliceEnabled)
        setSettingsRowEnabled(rowConversationManagement, canMutateTimeline())
        setSettingsRowEnabled(rowClearChat, clearChatEnabled)
        dialog.show()
    }

    private fun showConversationManagementDialog() {
        val conversations = conversationStore.all()
        val labels = conversations.map { conversation ->
            if (conversation.id == conversationStore.activeConversationId) {
                "✓ ${conversation.title}"
            } else {
                conversation.title
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.conversation_management)
            .setItems(labels) { _, index ->
                activateConversation(conversations[index].id)
            }
            .setPositiveButton(R.string.new_conversation) { _, _ ->
                val id = conversationStore.createConversation(listOf(createWelcomeMessage()))
                activateConversation(id)
            }
            .setNegativeButton(R.string.delete_current_conversation) { _, _ ->
                confirmDeleteCurrentConversation()
            }
            .show()
    }

    private fun confirmDeleteCurrentConversation() {
        val current = conversationStore.active
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_current_conversation)
            .setMessage(getString(R.string.delete_conversation_confirm, current.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                val removed = conversationStore.deleteConversation(
                    current.id,
                    listOf(createWelcomeMessage())
                ) ?: return@setPositiveButton
                removed.messages.filterIsInstance<ChatMessage.UserMessage>()
                    .flatMap { listOfNotNull(it.originalImageToken, it.previewImageToken) }
                    .forEach(::deleteImageIfUnreferenced)
                submitMessages()
                rebuildActiveConversationContext()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun activateConversation(id: Long) {
        if (!conversationStore.switchTo(id)) return
        pendingPrivacyAction = null
        submitMessages { scrollToBottom() }
        rebuildActiveConversationContext {
            Toast.makeText(
                this,
                getString(R.string.conversation_switched, conversationStore.active.title),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setSettingsRowEnabled(row: View, enabled: Boolean) {
        row.isEnabled = enabled
        row.isClickable = enabled
        row.alpha = if (enabled) 1f else 0.38f
    }

    /**
     * Pops up the slice-cap picker.  The slider drives a live preview of
     * the selected value; only on dialog "confirm" do we persist + push
     * the value to native.  Cancel = no-op.
     *
     * Live update path is cheap (no mmproj reload), but we still gate it
     * behind a confirm step so users don't accidentally regenerate cached
     * embeddings while dragging the knob.
     */
    private fun showImageSliceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_image_slice, null, false)
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.slider_image_slice)
        val tvValue = view.findViewById<android.widget.TextView>(R.id.tv_image_slice_value)

        val initial = LlamaEngine.getImageMaxSliceNums(this)
        slider.value = initial.toFloat()
        tvValue.text = initial.toString()
        slider.addOnChangeListener { _, value, _ -> tvValue.text = value.toInt().toString() }

        AlertDialog.Builder(this)
            .setTitle(R.string.image_slice_dialog_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = slider.value.toInt()
                lifecycleScope.launch {
                    engine.setImageMaxSliceNums(chosen)
                    val msgRes = if (engine.isVisionSupported) {
                        R.string.image_slice_apply_toast
                    } else {
                        R.string.image_slice_pending_toast
                    }
                    Toast.makeText(this@MainActivity, getString(msgRes, chosen), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearChatUI() {
        pendingPrivacyAction = null
        pendingImageViewModel.clearLocalAfterEngineReset()
        val oldTokens = messages.filterIsInstance<ChatMessage.UserMessage>()
            .flatMap { listOfNotNull(it.originalImageToken, it.previewImageToken) }
        messages.clear()
        val selectedModel = LlamaEngine.getSelectedModel(applicationContext)
        messages.add(createWelcomeMessage(selectedModel))
        oldTokens.forEach(::deleteImageIfUnreferenced)
        submitMessages()
    }

    private fun openOriginalImage(token: String) {
        startActivity(OriginalImageViewerActivity.intent(this, token))
    }

    private fun deleteImageIfUnreferenced(token: String) {
        if (token !in conversationStore.referencedImageTokens()) {
            val archiveWithoutImage = conversationStore.snapshot()
            conversationWriter.execute {
                try {
                    conversationArchiveStore.save(archiveWithoutImage)
                    originalImageCache.deleteToken(token)
                } catch (error: Exception) {
                    Log.e(TAG, "Could not remove an unreferenced conversation image", error)
                }
            }
        }
    }

    private fun createWelcomeMessage(
        model: ModelInfo = LlamaEngine.getSelectedModel(applicationContext)
    ) = ChatMessage.WelcomeCard(
        id = conversationStore.nextMessageId(),
        isTextOnly = model.isTextOnly,
        hasVisualContext = false
    )

    private fun canMutateTimeline(): Boolean =
        canClearCurrentChat() &&
            pendingImageViewModel.uiState.value is PendingImageUiState.Empty &&
            !isProcessingVideo

    private fun showMessageActions(message: ChatMessage) {
        if (message is ChatMessage.WelcomeCard) return
        val actions = MessageTimelineActionPolicy.availableActions(
            mutationInProgress = isClearing,
            destructiveMutationAllowed = canMutateTimeline()
        )
        if (actions.isEmpty()) {
            Toast.makeText(this, R.string.toast_wait_image_preprocessing, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = actions.map { action ->
            when (action) {
                MessageTimelineAction.EDIT -> getString(R.string.edit_message)
                MessageTimelineAction.DELETE -> getString(R.string.delete_message)
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.message_actions)
            .setItems(labels) { _, which ->
                when (actions[which]) {
                    MessageTimelineAction.EDIT -> showEditMessageDialog(message)
                    MessageTimelineAction.DELETE -> confirmDeleteMessage(message)
                }
            }
            .show()
    }

    private fun showEditMessageDialog(message: ChatMessage) {
        val currentText = when (message) {
            is ChatMessage.UserMessage -> message.text
            is ChatMessage.AiMessage -> message.text
            is ChatMessage.WelcomeCard -> return
        }
        val view = layoutInflater.inflate(R.layout.dialog_edit_message, null, false)
        val editText = view.findViewById<TextInputEditText>(R.id.et_edit_message)
        editText.filters = arrayOf(InputFilter.LengthFilter(MAX_EDIT_MESSAGE_CHARACTERS))
        editText.setText(currentText)
        editText.setSelection(editText.text?.length ?: 0)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.edit_message)
            .setView(view)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val replacement = editText.text?.toString()?.trim().orEmpty()
                if (replacement.isEmpty()) {
                    editText.error = getString(R.string.toast_empty_input)
                    return@setOnClickListener
                }
                dialog.dismiss()
                editMessage(message, replacement)
            }
        }
        dialog.show()
    }

    private fun editMessage(message: ChatMessage, replacement: String) {
        if (isClearing) return
        isClearing = true
        refreshInputControls()
        lifecycleScope.launch {
            var generationStarted = false
            try {
                cancelActiveWorkForTimelineEdit()
                val current = messages.firstOrNull { it.id == message.id }
                val mutation = when {
                    message is ChatMessage.UserMessage && current is ChatMessage.UserMessage ->
                        conversationStore.editUserAndTruncate(message.id, replacement)
                    message is ChatMessage.AiMessage && current is ChatMessage.AiMessage ->
                        conversationStore.editAssistantText(message.id, replacement)
                    else -> null
                } ?: return@launch
                mutation.removed.filterIsInstance<ChatMessage.UserMessage>()
                    .flatMap { listOfNotNull(it.originalImageToken, it.previewImageToken) }
                    .forEach(::deleteImageIfUnreferenced)
                submitMessages()

                if (!::engine.isInitialized || !isModelReady) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.toast_load_model_first,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                if (message is ChatMessage.AiMessage) {
                    replayActiveConversationContext()
                } else {
                    replayActiveConversationContext(skipMessageId = message.id)
                    val edited = messages.firstOrNull { it.id == message.id }
                        as? ChatMessage.UserMessage
                    edited?.originalImageToken?.takeUnless { edited.isVideo }?.let { token ->
                        pendingImageViewModel.replayCachedImage(token)
                    }
                    isClearing = false
                    refreshInputControls()
                    submitEditedUserMessage(message.id, replacement)
                    generationStarted = true
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to edit conversation message", error)
                Toast.makeText(
                    this@MainActivity,
                    getString(
                        R.string.conversation_rebuild_failed,
                        error.localizedMessage ?: getString(R.string.error_read_image)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (!generationStarted) {
                    isClearing = false
                    refreshInputControls()
                }
            }
        }
    }

    private suspend fun cancelActiveWorkForTimelineEdit() {
        pendingImageViewModel.cancelAndClear(PendingImageCancellationMode.USER_REMOVE)
        generationJob?.cancelAndJoin()
        generationJob = null
        localGuardJob?.cancelAndJoin()
        localGuardJob = null
        videoProcessingJob?.cancelAndJoin()
        videoProcessingJob = null
        isProcessingVideo = false
        pendingPrivacyAction = null
        isSubmitting = false
    }

    private suspend fun replayActiveConversationContext(skipMessageId: Long? = null) {
        engine.clearContext()
        for (message in conversationStore.replayMessages()) {
            if (message.id == skipMessageId) continue
            when (message) {
                is ChatMessage.UserMessage -> {
                    message.originalImageToken?.takeUnless { message.isVideo }?.let {
                        pendingImageViewModel.replayCachedImage(it)
                    }
                    engine.replayHistoryMessage(ModelHistoryRole.USER, message.text)
                }
                is ChatMessage.AiMessage -> {
                    val replayText = ModelHistoryText.assistant(message.text)
                    if (replayText.isNotBlank()) {
                        engine.replayHistoryMessage(ModelHistoryRole.ASSISTANT, replayText)
                    }
                }
                is ChatMessage.WelcomeCard -> Unit
            }
        }
    }

    private fun confirmDeleteMessage(message: ChatMessage) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_message)
            .setMessage(R.string.delete_message_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val mutation = conversationStore.deleteMessage(message.id)
                    ?: return@setPositiveButton
                mutation.removed.filterIsInstance<ChatMessage.UserMessage>()
                    .flatMap { listOfNotNull(it.originalImageToken, it.previewImageToken) }
                    .forEach(::deleteImageIfUnreferenced)
                submitMessages()
                rebuildActiveConversationContext()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun submitEditedUserMessage(messageId: Long, prompt: String) {
        val messageIndex = messages.indexOfFirst { it.id == messageId }
        val original = messages.getOrNull(messageIndex) as? ChatMessage.UserMessage ?: return
        val contentDecision = ContentSafetyPolicyEngine.evaluate(
            LocalContentSafetyClassifier.classify(prompt)
        )
        if (contentDecision != ContentSafetyDecision.ALLOW) {
            val reply = when (contentDecision) {
                ContentSafetyDecision.WARNING -> {
                    pendingPrivacyAction = PendingPrivacyAction.SubmitPrompt(prompt, messageId)
                    messages[messageIndex] = original.copy(requiresPrivacyConfirmation = true)
                    submitMessages()
                    return
                }
                ContentSafetyDecision.BLOCK -> getString(R.string.response_illegal_refusal)
                ContentSafetyDecision.REVIEW -> getString(R.string.response_safety_review)
                ContentSafetyDecision.ALLOW -> error("unreachable")
            }
            messages[messageIndex] = original.copy(includeInModelContext = false)
            appendLocalReply(reply)
            return
        }
        val visualPlan = LocalGuardReplyPolicy.plan(engine.evaluateVisualPrompt(prompt))
        if (visualPlan.destination == PromptDestination.LOCAL_ONLY) {
            messages[messageIndex] = original.copy(includeInModelContext = false)
            val reply = when (requireNotNull(visualPlan.localReplyKind)) {
                LocalGuardReplyKind.NO_VISUAL_CONTEXT ->
                    getString(R.string.response_blocked_no_visual_context)
                LocalGuardReplyKind.UNCERTAIN_VISUAL_REQUEST ->
                    getString(R.string.response_uncertain_visual_request)
            }
            appendLocalReply(reply)
            return
        }
        submitPromptToModel(
            prompt,
            PendingImageUiState.Empty,
            displayUserMessage = false,
            existingUserMessageId = messageId
        )
    }

    private fun appendLocalReply(reply: String) {
        val aiId = conversationStore.nextMessageId()
        messages.add(
            ChatMessage.AiMessage(
                id = aiId,
                text = reply,
                includeInModelContext = false
            )
        )
        submitMessages { scrollToBottom() }
        isSubmitting = false
        refreshInputControls()
    }

    private fun rebuildActiveConversationContext(
        skipMessageId: Long? = null,
        onReady: (suspend () -> Unit)? = null
    ) {
        if (isClearing || !::engine.isInitialized || !isModelReady) return
        isClearing = true
        refreshInputControls()
        lifecycleScope.launch {
            try {
                pendingImageViewModel.cancelAndClear()
                videoProcessingJob?.cancelAndJoin()
                videoProcessingJob = null
                replayActiveConversationContext(skipMessageId)
                submitMessages()
                isClearing = false
                refreshInputControls()
                onReady?.invoke()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to rebuild conversation context", error)
                try {
                    if (engine.state.value is LlamaState.ModelReady) engine.clearContext()
                } catch (resetError: Exception) {
                    Log.e(TAG, "Failed to recover after conversation rebuild", resetError)
                }
                Toast.makeText(
                    this@MainActivity,
                    getString(
                        R.string.conversation_rebuild_failed,
                        error.localizedMessage ?: getString(R.string.error_read_image)
                    ),
                    Toast.LENGTH_LONG
                ).show()
                isClearing = false
                refreshInputControls()
            }
        }
    }

    private fun removePendingImage() {
        if (isClearing) return
        val token = when (val state = pendingImageViewModel.uiState.value) {
            is PendingImageUiState.Preprocessing -> state.attachment.originalImageToken
            is PendingImageUiState.Ready -> state.attachment.originalImageToken
            else -> null
        }
        isClearing = true
        refreshInputControls()
        lifecycleScope.launch {
            try {
                pendingImageViewModel.cancelAndClear(PendingImageCancellationMode.USER_REMOVE)
                engine.clearContext()
                for (message in conversationStore.replayMessages()) {
                    when (message) {
                        is ChatMessage.UserMessage -> {
                            message.originalImageToken?.takeUnless { message.isVideo }?.let {
                                pendingImageViewModel.replayCachedImage(it)
                            }
                            engine.replayHistoryMessage(ModelHistoryRole.USER, message.text)
                        }
                        is ChatMessage.AiMessage -> {
                            val replayText = ModelHistoryText.assistant(message.text)
                            if (replayText.isNotBlank()) {
                                engine.replayHistoryMessage(ModelHistoryRole.ASSISTANT, replayText)
                            }
                        }
                        is ChatMessage.WelcomeCard -> Unit
                    }
                }
                token?.let(::deleteImageIfUnreferenced)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to remove pending image", error)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.conversation_rebuild_failed, error.localizedMessage ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isClearing = false
                renderPendingImage()
                refreshInputControls()
            }
        }
    }

    private fun clearChat() {
        if (isClearing) return
        isClearing = true
        refreshInputControls()

        lifecycleScope.launch {
            try {
                pendingImageViewModel.cancelAndClear()
                videoProcessingJob?.cancelAndJoin()
                videoProcessingJob = null
                withContext(Dispatchers.IO) {
                    engine.clearContext()
                }
                clearChatUI()
                Toast.makeText(
                    this@MainActivity,
                    R.string.clear_chat_toast,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing context", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_clear_chat_failed, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isClearing = false
                refreshInputControls()
            }
        }
    }

    private fun initEngine() {
        lifecycleScope.launch(Dispatchers.Default) {
            engine = LlamaEngine.getInstance(applicationContext)
            withContext(Dispatchers.Main) {
                observeEngineState()
                observeVisualContext()
            }
        }
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            engine.state.collect { state ->
                currentEngineState = state
                when (state) {
                    is LlamaState.Uninitialized,
                    is LlamaState.Initializing -> {
                        isModelReady = false
                    }
                    is LlamaState.Initialized -> {
                        isModelReady = false
                        if (!hasAutoLoaded) {
                            hasAutoLoaded = true
                            loadDefaultModel()
                        }
                    }
                    is LlamaState.LoadingModel -> {
                        isModelReady = false
                    }
                    is LlamaState.ModelReady -> {
                        isModelReady = true
                        loadedModelId = LlamaEngine.getSelectedModel(applicationContext).id
                        updateUIForModelType()
                    }
                    is LlamaState.ProcessingSystemPrompt,
                    is LlamaState.ProcessingUserPrompt,
                    is LlamaState.Generating -> {
                        isModelReady = true
                    }
                    is LlamaState.PrefillingImage -> {
                        isModelReady = true
                    }
                    is LlamaState.UnloadingModel -> {
                        isModelReady = false
                    }
                    is LlamaState.Error -> {
                        isModelReady = false
                    }
                }
                refreshInputControls()
            }
        }
    }

    private fun observeVisualContext() {
        lifecycleScope.launch {
            engine.hasVisualContext.collect {
                refreshWelcomeCard(
                    LlamaEngine.getSelectedModel(applicationContext).isTextOnly
                )
            }
        }
    }

    private fun refreshInputControls() {
        if (!::etInput.isInitialized) return

        val engineBusy = isSubmitting || isClearing || when (currentEngineState) {
            is LlamaState.ModelReady,
            is LlamaState.PrefillingImage -> false
            else -> true
        }
        val controls = pendingImageViewModel.controls(
            modelReady = isModelReady,
            engineBusy = engineBusy,
            videoProcessing = isProcessingVideo,
            hasText = etInput.text?.toString()?.isNotBlank() == true
        )
        val visionSupported = ::engine.isInitialized && engine.isVisionSupported
        val modelManagerSafe = isModelManagerSafe()
        val clearChatSafe = canClearCurrentChat()

        etInput.isEnabled = controls.textEnabled
        btnSend.isEnabled = controls.sendEnabled
        btnImage.isEnabled = controls.mediaEnabled && visionSupported
        btnCamera.isEnabled = controls.mediaEnabled && visionSupported
        btnSettings.isEnabled = modelManagerSafe || clearChatSafe
    }

    private fun isModelManagerSafe(): Boolean {
        val hasPendingImage =
            pendingImageViewModel.uiState.value !is PendingImageUiState.Empty
        return !hasPendingImage && !isSubmitting && !isClearing &&
            !isProcessingVideo &&
            when (currentEngineState) {
                is LlamaState.LoadingModel,
                is LlamaState.ProcessingSystemPrompt,
                is LlamaState.ProcessingUserPrompt,
                is LlamaState.PrefillingImage,
                is LlamaState.Generating,
                is LlamaState.UnloadingModel -> false
                else -> true
            }
    }

    private fun canChangeImageSlices(): Boolean =
        ::engine.isInitialized && engine.isVisionSupported && isModelManagerSafe()

    private fun canClearCurrentChat(): Boolean =
        isModelReady && !isSubmitting && !isClearing &&
            (currentEngineState is LlamaState.ModelReady ||
                currentEngineState is LlamaState.PrefillingImage)

    private fun shouldRedirectToTts(): Boolean {
        val model = LlamaEngine.getSelectedModel(applicationContext)
        return model.isTts
    }

    private fun updateUIForModelType() {
        val model = LlamaEngine.getSelectedModel(applicationContext)
        val isVision = engine.isVisionSupported

        tvTitle.setText(if (isVision) R.string.app_title else R.string.app_title_text)
        btnImage.visibility = if (isVision) View.VISIBLE else View.GONE
        btnCamera.visibility = if (isVision) View.VISIBLE else View.GONE
        refreshWelcomeCard(model.isTextOnly)
        refreshInputControls()
    }

    private fun refreshWelcomeCard(isTextOnly: Boolean) {
        val welcomeIndex = messages.indexOfFirst { it is ChatMessage.WelcomeCard }
        if (welcomeIndex >= 0) {
            messages[welcomeIndex] = ChatMessage.WelcomeCard(
                isTextOnly = isTextOnly,
                hasVisualContext = ::engine.isInitialized && engine.hasVisualContext.value
            )
            submitMessages()
        }
    }

    private fun loadDefaultModel() {
        val ctx = applicationContext
        val model = LlamaEngine.getSelectedModel(ctx)
        val ggufFile = File(LlamaEngine.modelPath(ctx))
        val mmprojPathStr = LlamaEngine.mmprojPath(ctx)
        val mmprojFile = mmprojPathStr?.let { File(it) }

        val ggufMissing = !ggufFile.exists()
        val mmprojMissing = !model.isTextOnly && (mmprojFile == null || !mmprojFile.exists())

        if (ggufMissing || mmprojMissing) {
            if (ModelDownloadPromptPolicy.shouldPrompt(
                    ggufMissing = ggufMissing,
                    mmprojMissing = mmprojMissing,
                    downloadRunning = ModelDownloadController.isRunning
                )
            ) {
                promptDownloadModels(
                    ggufMissing = ggufMissing,
                    mmprojMissing = mmprojMissing
                )
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mmprojArg = if (mmprojFile != null && mmprojFile.exists()) mmprojFile.absolutePath else null
                engine.loadModel(ggufFile.absolutePath, mmprojArg)
                loadedModelId = model.id
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                engine.resetToInitialized()
                hasAutoLoaded = false
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_model_load_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun promptDownloadModels(ggufMissing: Boolean, mmprojMissing: Boolean) {
        val message = when {
            ggufMissing && mmprojMissing ->
                getString(R.string.download_prompt_all_missing)
            mmprojMissing ->
                getString(R.string.download_prompt_mmproj_missing)
            else ->
                getString(R.string.download_prompt_incomplete)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.download_prompt_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.go_download) { _, _ ->
                startActivity(Intent(this, ModelManagerActivity::class.java))
            }
            .setNegativeButton(R.string.later) { _, _ ->
                Toast.makeText(
                    this,
                    R.string.download_prompt_hint,
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private val getMedia = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedMedia(it) }
    }

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = pendingCameraUri
        val file = pendingCameraFile
        pendingCameraUri = null
        pendingCameraFile = null
        if (captured && uri != null && file != null) {
            handleSelectedImage(uri, file)
        } else {
            deleteCameraCacheFile(file)
        }
    }

    private fun handleSelectedMedia(uri: Uri) {
        if (!isModelReady || currentEngineState !is LlamaState.ModelReady) {
            Toast.makeText(this, R.string.toast_load_model_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingImageViewModel.uiState.value !is PendingImageUiState.Empty) {
            Toast.makeText(this, R.string.toast_wait_image_preprocessing, Toast.LENGTH_SHORT).show()
            return
        }
        val mime = contentResolver.getType(uri).orEmpty()
        when {
            mime.startsWith("video/") -> handleSelectedVideo(uri)
            mime.startsWith("image/") || mime.isEmpty() -> handleSelectedImage(uri)
            else -> {
                Toast.makeText(this, getString(R.string.toast_unsupported_file, mime), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchCameraCapture() {
        if (!isModelReady || currentEngineState !is LlamaState.ModelReady) {
            Toast.makeText(this, R.string.toast_load_model_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingImageViewModel.uiState.value !is PendingImageUiState.Empty) {
            Toast.makeText(this, R.string.toast_wait_image_preprocessing, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val cameraDir = File(cacheDir, CAMERA_CACHE_DIRECTORY)
            if (!cameraDir.exists() && !cameraDir.mkdirs()) {
                throw IOException(getString(R.string.error_create_camera_file))
            }
            val captureFile = File.createTempFile("capture-", ".jpg", cameraDir)
            val captureUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                captureFile
            )
            pendingCameraFile = captureFile
            pendingCameraUri = captureUri
            takePicture.launch(captureUri)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No camera app can handle image capture", e)
            clearPendingCameraCapture()
            Toast.makeText(
                this,
                getString(R.string.toast_camera_failed, e.localizedMessage ?: "No camera app"),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create camera capture", e)
            clearPendingCameraCapture()
            Toast.makeText(
                this,
                getString(
                    R.string.toast_camera_failed,
                    e.localizedMessage ?: getString(R.string.error_create_camera_file)
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleSelectedImage(uri: Uri, cameraCacheFile: File? = null) {
        if (!pendingImageViewModel.start(uri, cameraCacheFile)) {
            Toast.makeText(this, R.string.toast_wait_image_preprocessing, Toast.LENGTH_SHORT).show()
            return
        }
        renderPendingImage(pendingImageViewModel.uiState.value)
        refreshInputControls()
    }

    private fun renderPendingImage(
        state: PendingImageUiState = pendingImageViewModel.uiState.value
    ) {
        if (state is PendingImageUiState.Empty) {
            pendingImagePanel.visibility = View.GONE
            ivPendingImage.setImageDrawable(null)
            return
        }

        pendingImagePanel.visibility = View.VISIBLE
        val attachment = when (state) {
            is PendingImageUiState.Preprocessing -> state.attachment
            is PendingImageUiState.Ready -> state.attachment
            else -> null
        }
        if (attachment == null) {
            ivPendingImage.setImageDrawable(null)
            tvPendingImageInfo.setText(R.string.image_preprocessing)
        } else {
            ivPendingImage.setImageBitmap(attachment.thumbnail)
            tvPendingImageInfo.text = attachment.imageInfo
        }

        when (state) {
            is PendingImageUiState.LoadingPreview,
            is PendingImageUiState.Preprocessing,
            PendingImageUiState.Clearing -> {
                pendingImageScrim.visibility = View.VISIBLE
                progressPendingImage.visibility = View.VISIBLE
                progressPendingImage.isIndeterminate = true
                tvPendingImageStatus.setText(R.string.image_preprocessing_wait)
            }
            is PendingImageUiState.Ready -> {
                pendingImageScrim.visibility = View.GONE
                progressPendingImage.visibility = View.GONE
                tvPendingImageStatus.setText(R.string.image_ready_view_original)
            }
            PendingImageUiState.Empty -> Unit
        }
        ivPendingImage.isClickable = attachment != null
        btnRemovePendingImage.isEnabled = state !is PendingImageUiState.Clearing
        btnRemovePendingImage.visibility = if (state is PendingImageUiState.Empty) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun restorePendingCameraCapture(savedInstanceState: Bundle?) {
        val uriText = savedInstanceState?.getString(STATE_CAMERA_URI) ?: return
        val savedFileName = savedInstanceState.getString(STATE_CAMERA_FILE_NAME) ?: return
        if (savedFileName != File(savedFileName).name) return

        val restoredUri = Uri.parse(uriText)
        if (
            restoredUri.scheme != "content" ||
            restoredUri.authority != "${packageName}.fileprovider"
        ) {
            return
        }
        val restoredFile = File(File(cacheDir, CAMERA_CACHE_DIRECTORY), savedFileName)
        if (!restoredFile.isFile) return

        pendingCameraUri = restoredUri
        pendingCameraFile = restoredFile
    }

    private fun clearPendingCameraCapture() {
        deleteCameraCacheFile(pendingCameraFile)
        pendingCameraUri = null
        pendingCameraFile = null
    }

    private fun deleteCameraCacheFile(file: File?) {
        if (file == null) return
        try {
            val cameraDir = File(cacheDir, CAMERA_CACHE_DIRECTORY).canonicalFile
            val target = file.canonicalFile
            if (target.parentFile == cameraDir && target.isFile && !target.delete()) {
                Log.w(TAG, "Unable to delete camera cache file: ${target.name}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Unable to resolve camera cache file", e)
        }
    }

    /**
     * Video-understanding pipeline (iOS-equivalent
     * MBHomeViewController+CaptureVideo.processVideoFrame):
     * extract up to 64 uniformly-sampled frames off the IO dispatcher,
     * append a single chat cell with the first frame as thumbnail,
     * then hand the frames to [LlamaEngine.prefillVideoFrames] which
     * loops `prefillImage(...)` under a temporary slice=1 cap.
     *
     * Gated to MiniCPM-V-4.6 because that's where iOS enables the
     * feature and where the native nCtx bump to 8192 takes effect
     * (see prepare() in llama_jni.cpp).
     */
    private fun handleSelectedVideo(uri: Uri) {
        if (!engine.isVideoUnderstandingSupported) {
            Toast.makeText(this,
                R.string.video_only_v46,
                Toast.LENGTH_LONG).show()
            return
        }

        isProcessingVideo = true
        val msgId = conversationStore.nextMessageId()
        refreshInputControls()
        videoProcessingJob = lifecycleScope.launch(Dispatchers.IO) {
            val startNs = System.nanoTime()
            var completed = false
            var failure: Exception? = null
            var videoPreviewToken: String? = null
            try {
                val extracted = VideoFrameExtractor.extract(applicationContext, uri)
                val info = VideoFrameExtractor.formatVideoInfo(applicationContext, extracted)
                videoPreviewToken = cachePreview(extracted.thumbnail)
                Log.i(TAG, "Video info: $info")

                withContext(Dispatchers.Main) {
                    val videoMessage = ChatMessage.UserMessage(
                        id = msgId,
                        text = "",
                        imageBitmap = extracted.thumbnail,
                        imageInfo = info,
                        previewImageToken = videoPreviewToken,
                        isPrefilling = true,
                        isVideo = true
                    )
                    messages.add(videoMessage)
                    submitMessages {
                        scrollToBottom()
                    }
                }

                engine.prefillVideoFrames(extracted.frames) { current, total ->
                    withContext(Dispatchers.Main) {
                        val index = messages.indexOfFirst { it.id == msgId }
                        if (index >= 0) {
                            val cur = messages[index] as ChatMessage.UserMessage
                            messages[index] = cur.copy(
                                imageInfo = getString(R.string.video_processing_progress, info, current, total)
                            )
                            submitMessages()
                        }
                    }
                }

                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                withContext(Dispatchers.Main) {
                    val index = messages.indexOfFirst { it.id == msgId }
                    if (index >= 0) {
                        val cur = messages[index] as ChatMessage.UserMessage
                        messages[index] = cur.copy(
                            imageInfo = getString(R.string.video_preprocessing_done, info, elapsedMs / 1000.0),
                            isPrefilling = false
                        )
                        submitMessages()
                    }
                }
                completed = true
            } catch (e: CancellationException) {
                Log.i(TAG, "Video preprocessing was cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error processing video", e)
                failure = e
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isProcessingVideo = false
                    if (!completed) {
                        val index = messages.indexOfFirst { it.id == msgId }
                        if (index >= 0) {
                            messages.removeAt(index)
                            submitMessages()
                        }
                        videoPreviewToken?.let(::deleteImageIfUnreferenced)
                    }
                    videoProcessingJob = null
                    refreshInputControls()
                    failure?.let { error ->
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_video_failed, error.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun handleUserInput() {
        val userMsg = etInput.text.toString().trim()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, R.string.toast_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        val pendingState = pendingImageViewModel.uiState.value
        if (
            pendingState is PendingImageUiState.LoadingPreview ||
            pendingState is PendingImageUiState.Preprocessing ||
            pendingState is PendingImageUiState.Clearing
        ) {
            Toast.makeText(this, R.string.toast_wait_image_preprocessing, Toast.LENGTH_SHORT).show()
            return
        }
        if (
            !isModelReady ||
            currentEngineState !is LlamaState.ModelReady ||
            isSubmitting ||
            isClearing
        ) {
            Toast.makeText(this, R.string.toast_load_model_first, Toast.LENGTH_SHORT).show()
            return
        }

        pendingPrivacyAction?.let { pendingAction ->
            if (pendingAction is PendingPrivacyAction.RevealResponse) {
                handlePrivacyOutputConfirmation(userMsg, pendingAction)
            }
            return
        }

        val contentDecision = ContentSafetyPolicyEngine.evaluate(
            LocalContentSafetyClassifier.classify(userMsg)
        )
        when (contentDecision) {
            ContentSafetyDecision.WARNING -> {
                showPrivacyInputConfirmation(userMsg)
                return
            }
            ContentSafetyDecision.BLOCK -> {
                showLocalOnlyConversation(userMsg, getString(R.string.response_illegal_refusal))
                return
            }
            ContentSafetyDecision.REVIEW -> {
                showLocalOnlyConversation(userMsg, getString(R.string.response_safety_review))
                return
            }
            ContentSafetyDecision.ALLOW -> Unit
        }

        val dispatchPlan = LocalGuardReplyPolicy.plan(engine.evaluateVisualPrompt(userMsg))
        if (dispatchPlan.destination == PromptDestination.LOCAL_ONLY) {
            showLocalGuardReply(userMsg, requireNotNull(dispatchPlan.localReplyKind))
            return
        }

        submitPromptToModel(userMsg, pendingState, displayUserMessage = true)
    }

    private fun handlePrivacyOutputConfirmation(
        confirmationText: String,
        pendingAction: PendingPrivacyAction.RevealResponse
    ) {
        when (ExplicitConfirmationParser.parse(confirmationText)) {
            ConfirmationDecision.CONFIRM -> {
                pendingPrivacyAction = null
                showLocalOnlyConversation(
                    confirmationText,
                    pendingAction.response,
                    streamReply = false
                )
            }
            ConfirmationDecision.DECLINE -> {
                pendingPrivacyAction = null
                showLocalOnlyConversation(
                    confirmationText,
                    getString(R.string.response_privacy_cancelled)
                )
            }
            ConfirmationDecision.INVALID -> {
                showLocalOnlyConversation(
                    confirmationText,
                    getString(R.string.response_privacy_confirmation_required)
                )
            }
        }
    }

    private fun showPrivacyInputConfirmation(userMsg: String) {
        etInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etInput.windowToken, 0)
        etInput.text = null
        isSubmitting = true
        refreshInputControls()
        collapseAppBar()

        val messageId = conversationStore.nextMessageId()
        pendingPrivacyAction = PendingPrivacyAction.SubmitPrompt(userMsg, messageId)
        messages.add(
            ChatMessage.UserMessage(
                id = messageId,
                text = userMsg,
                requiresPrivacyConfirmation = true
            )
        )
        submitMessages { scrollToBottom() }
    }

    private fun handlePrivacyInputChoice(messageId: Long, approved: Boolean) {
        val pending = pendingPrivacyAction as? PendingPrivacyAction.SubmitPrompt ?: return
        when (
            PrivacyInputConfirmationPolicy.resolve(
                pendingMessageId = pending.messageId,
                selectedMessageId = messageId,
                approved = approved
            )
        ) {
            PrivacyInputChoiceAction.SUBMIT -> {
                val submissionStarted = submitPromptToModel(
                    pending.prompt,
                    pendingImageViewModel.uiState.value,
                    displayUserMessage = false,
                    existingUserMessageId = pending.messageId
                )
                if (submissionStarted) {
                    pendingPrivacyAction = null
                }
            }
            PrivacyInputChoiceAction.DELETE -> {
                pendingPrivacyAction = null
                val index = messages.indexOfFirst { it.id == pending.messageId }
                if (index >= 0) {
                    messages.removeAt(index)
                }
                submitMessages()
                isSubmitting = false
                refreshInputControls()
            }
            PrivacyInputChoiceAction.IGNORE -> Unit
        }
    }

    private fun submitPromptToModel(
        userMsg: String,
        pendingState: PendingImageUiState,
        displayUserMessage: Boolean,
        existingUserMessageId: Long? = null
    ): Boolean {

        val attachment = if (pendingState is PendingImageUiState.Ready) {
            pendingImageViewModel.consumeReady().also { consumed ->
                if (consumed == null) {
                    Log.e(TAG, "Ready pending image could not be consumed")
                }
            }
        } else {
            null
        }
        if (pendingState is PendingImageUiState.Ready && attachment == null) {
            Toast.makeText(
                this,
                getString(R.string.toast_image_failed, getString(R.string.error_read_image)),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        etInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etInput.windowToken, 0)

        etInput.text = null
        isSubmitting = true
        refreshInputControls()

        collapseAppBar()

        renderPendingImage(pendingImageViewModel.uiState.value)
        val previewToken = attachment?.thumbnail?.let(::cachePreview)
        var submittedUserMessageId = existingUserMessageId
        if (displayUserMessage) {
            val userMessage = ChatMessage.UserMessage(
                id = conversationStore.nextMessageId(),
                text = userMsg,
                imageBitmap = attachment?.thumbnail,
                imageInfo = attachment?.imageInfo,
                originalImageToken = attachment?.originalImageToken,
                previewImageToken = previewToken
            )
            submittedUserMessageId = userMessage.id
            messages.add(userMessage)
            conversationStore.updateTitleFromFirstUserMessage()
            submitMessages {
                scrollToBottom()
            }
        } else if (existingUserMessageId != null) {
            val existingIndex = messages.indexOfFirst { it.id == existingUserMessageId }
            val existing = messages.getOrNull(existingIndex) as? ChatMessage.UserMessage
            if (existingIndex >= 0 && existing != null) {
                messages[existingIndex] = existing.confirmedForSubmission(
                    attachment = attachment,
                    persistedPreviewToken = previewToken
                )
                conversationStore.updateTitleFromFirstUserMessage()
                submitMessages { scrollToBottom() }
            }
        }

        val aiMsgId = conversationStore.nextMessageId()
        val aiMessage = ChatMessage.AiMessage(id = aiMsgId, text = "", isGenerating = true)
        messages.add(aiMessage)
        chatAdapter.setActiveAiMessage(aiMsgId)
        submitMessages {
            scrollToBottom()
        }

        val generationHadVisualContext = engine.hasVisualContext.value
        val conversationIdAtSubmission = conversationStore.activeConversationId
        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            val fullResponse = StringBuilder()
            var ragRunId: String? = null
            var ragSources: List<RetrievedChunk> = emptyList()
            var ragTransaction: RagTurnTransaction? = null
            var usesPreparedPrompt = false
            val latencyTrace = RagLatencyTrace.start(UUID.randomUUID().toString())
            var traceResult = RagTraceResult.FAILED
            try {
                latencyTrace.begin(RagPhase.ROUTE)
                val turnPlan = try {
                    withTimeoutOrNull(RAG_PLANNING_TIMEOUT_MS) {
                        (application as MiniCPMApplication).ragCoordinator
                            .plan(
                                conversationIdAtSubmission,
                                userMsg,
                                tokenCounter = object : RagPromptTokenCounter {
                                    override suspend fun count(text: String): Int =
                                        engine.countPromptTokens(text)

                                    override suspend fun remainingContextTokens(): Int =
                                        engine.remainingContextTokens()
                                },
                                onStage = { stage ->
                                    updateRagGenerationStage(
                                        aiMsgId,
                                        when (stage) {
                                            RagPlanningStage.RETRIEVING -> RagGenerationStage.RETRIEVING
                                            RagPlanningStage.ORGANIZING -> RagGenerationStage.ORGANIZING
                                        },
                                    )
                                },
                            )
                    } ?: RagTurnPlan.Failed(com.example.minicpm_v_demo.rag.RagTurnFailure.STATE_UNAVAILABLE)
                } finally {
                    latencyTrace.end(RagPhase.ROUTE)
                }
                val plainModelPrompt = turnPlan.plainModelPromptOrNull(userMsg)
                val modelPrompt = if (plainModelPrompt != null) {
                    updateRagGenerationStage(aiMsgId, null)
                    traceResult = RagTraceResult.PASS_THROUGH
                    plainModelPrompt
                } else when (turnPlan) {
                    RagTurnPlan.Disabled,
                    RagTurnPlan.NoRetrieval,
                    RagTurnPlan.NoEvidence -> {
                        error("Plain-model RAG turn was not handled")
                    }
                    is RagTurnPlan.Ready -> {
                        val app = application as MiniCPMApplication
                        val groundednessReady =
                            app.ragGuardModelManager.openInstalled() != null
                        if (!groundednessReady) {
                            traceResult = RagTraceResult.PASS_THROUGH
                            userMsg
                        } else {
                            latencyTrace.begin(RagPhase.PREFILL)
                            val checkpoint = try {
                                engine.beginEphemeralTurn()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                app.lowLatencyRagRuntimeGate.disable()
                                null
                            } finally {
                                latencyTrace.end(RagPhase.PREFILL)
                            }
                            if (checkpoint == null) {
                                traceResult = RagTraceResult.PASS_THROUGH
                                userMsg
                            } else {
                                traceResult = RagTraceResult.AUGMENTED
                                ragRunId = turnPlan.runId
                                ragSources = turnPlan.citations.toList()
                                latencyTrace.recordCandidateCount(ragSources.size)
                                latencyTrace.recordEvidenceTokenCount(turnPlan.evidenceTokenCount)
                                ragTransaction = RagTurnTransaction(engine, checkpoint)
                                usesPreparedPrompt = true
                                turnPlan.prompt
                            }
                        }
                    }
                    RagTurnPlan.NoSelection,
                    RagTurnPlan.Indexing,
                    RagTurnPlan.ModelRequired,
                    is RagTurnPlan.Failed -> {
                        // RAG is an optional augmentation. Any unavailable/not-ready path
                        // falls back to the original prompt without showing a RAG notice.
                        traceResult = RagTraceResult.PASS_THROUGH
                        userMsg
                    }
                }
                modelPrompt?.let { prompt ->
                    updateRagGenerationStage(
                        aiMsgId,
                        if (usesPreparedPrompt) RagGenerationStage.GENERATING else null,
                    )
                    var waitingForFirstToken = true
                    latencyTrace.begin(RagPhase.TTFT)
                    val tokens = if (usesPreparedPrompt) {
                        engine.sendPreparedPrompt(
                            modelPrompt = prompt,
                            originalUserTextForSafety = userMsg,
                        )
                    } else {
                        engine.sendUserPrompt(prompt)
                    }
                    tokens.collect { token ->
                        if (waitingForFirstToken) {
                            latencyTrace.end(RagPhase.TTFT)
                            waitingForFirstToken = false
                        }
                        fullResponse.append(token)
                    }
                }
                if (ragRunId != null && ragTransaction != null && fullResponse.isNotBlank()) {
                    val app = application as MiniCPMApplication
                    val installedClassifier = app.ragGuardModelManager.openInstalled()
                    val profile = CurrentGroundednessCalibration.profile
                    val reviewed = if (installedClassifier != null) {
                        RagReviewedGenerator(
                            classifier = WatchdogGroundednessClassifier(
                                delegate = GroundednessClassifier { question, sources, answer ->
                                    installedClassifier.classifyGroundedness(question, sources, answer)
                                },
                                timeoutMs = RAG_REVIEW_TIMEOUT_MS,
                            ),
                            profile = profile,
                        ).review(userMsg, ragSources, fullResponse.toString()) { correctionPrompt ->
                            ragTransaction?.rollback(
                                keepUserInHistory = false,
                                originalUserText = userMsg,
                            )
                            val correctionCheckpoint = engine.beginEphemeralTurn()
                            ragTransaction = RagTurnTransaction(engine, correctionCheckpoint)
                            val correctedResponse = StringBuilder()
                            engine.sendPreparedPrompt(
                                modelPrompt = correctionPrompt,
                                originalUserTextForSafety = userMsg,
                            ).collect { token ->
                                correctedResponse.append(token)
                            }
                            correctedResponse.toString()
                        }
                    } else {
                        ReviewedRagGeneration.FallbackToNormalGeneration
                    }
                    when (reviewed) {
                        is ReviewedRagGeneration.Accepted -> {
                            fullResponse.clear()
                            fullResponse.append(reviewed.answer)
                        }
                        ReviewedRagGeneration.FallbackToNormalGeneration -> {
                            ragTransaction?.rollback(
                                keepUserInHistory = false,
                                originalUserText = userMsg,
                            )
                            ragTransaction = null
                            ragRunId = null
                            ragSources = emptyList()
                            usesPreparedPrompt = false
                            traceResult = RagTraceResult.PASS_THROUGH
                            fullResponse.clear()
                            engine.sendUserPrompt(userMsg).collect { token ->
                                fullResponse.append(token)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                traceResult = RagTraceResult.CANCELLED
                Log.i(TAG, "Text generation was cancelled")
                ragTransaction?.rollback(
                    keepUserInHistory = true,
                    originalUserText = userMsg,
                )
                throw e
            } catch (e: Exception) {
                traceResult = RagTraceResult.FAILED
                Log.e(TAG, "Text generation failed", e)
                ragTransaction?.rollback(
                    keepUserInHistory = true,
                    originalUserText = userMsg,
                )
            } finally {
                Log.i(
                    TAG,
                    RagLatencyLogFormatter.format(latencyTrace.snapshot(), traceResult),
                )
                withContext(NonCancellable + Dispatchers.Main) {
                    val index = messages.indexOfFirst { it.id == aiMsgId }
                    val candidateResponse = fullResponse.toString()
                    if (traceResult == RagTraceResult.CANCELLED) {
                        if (index >= 0) messages.removeAt(index)
                        chatAdapter.setGeneratingDone(aiMsgId)
                        chatAdapter.clearActiveAiMessage()
                        submitMessages()
                        isSubmitting = false
                        generationJob = null
                        refreshInputControls()
                        return@withContext
                    }
                    val baselineVisualDecision = engine.evaluateVisualResponse(
                        response = candidateResponse,
                        hadVisualContext = generationHadVisualContext
                    )
                    val responseDecision = RagVisualGroundingPolicy.resolve(
                        baseline = baselineVisualDecision,
                        response = candidateResponse,
                        sources = if (ragRunId != null) ragSources else emptyList(),
                    )
                    val contentDecision = ContentSafetyPolicyEngine.evaluate(
                        LocalContentSafetyClassifier.classify(candidateResponse)
                    )
                    val displayAction = ContentSafetyDisplayPolicy.plan(
                        responseDecision,
                        contentDecision
                    )
                    val displayedResponse = when (displayAction) {
                        ContentDisplayAction.SHOW_CANDIDATE -> candidateResponse
                        ContentDisplayAction.SHOW_VISUAL_GUARD -> {
                            Log.w(
                                TAG,
                                "Generated response hidden by visual grounding guard: " +
                                    responseDecision.name
                            )
                            getString(R.string.response_blocked_no_visual_context)
                        }
                        ContentDisplayAction.REQUEST_PRIVACY_CONFIRMATION -> {
                            pendingPrivacyAction = PendingPrivacyAction.RevealResponse(
                                candidateResponse
                            )
                            getString(R.string.response_privacy_output_confirmation)
                        }
                        ContentDisplayAction.SHOW_ILLEGAL_REFUSAL -> {
                            Log.w(TAG, "Generated response hidden by local content safety policy")
                            getString(R.string.response_illegal_refusal)
                        }
                        ContentDisplayAction.SHOW_REVIEW_FALLBACK -> {
                            Log.w(TAG, "Generated response requires safety review and was hidden")
                            getString(R.string.response_safety_review)
                        }
                    }
                    val responseAccepted =
                        displayAction == ContentDisplayAction.SHOW_CANDIDATE &&
                            candidateResponse.isNotBlank() &&
                            traceResult != RagTraceResult.FAILED
                    if (responseAccepted) {
                        ragTransaction?.commit(userMsg, candidateResponse)
                    } else {
                        ragTransaction?.rollback(
                            keepUserInHistory = true,
                            originalUserText = userMsg,
                        )
                    }
                    val citationSnapshots = if (responseAccepted && ragRunId != null) {
                        CitationValidator.validate(candidateResponse, ragSources).map { citation ->
                            CitationRef(
                                messageId = aiMsgId,
                                sourceId = citation.sourceId,
                                chunkId = citation.source.chunkId,
                                documentId = citation.source.documentId,
                                documentNameSnapshot = citation.source.displayName,
                                locator = citation.source.locator,
                                quotedText = citation.source.text.take(MAX_CITATION_QUOTE_CHARS),
                                retrievalScore = citation.source.score.toDouble(),
                                retrievalVersion = RAG_RETRIEVAL_VERSION,
                            )
                        }.toList()
                    } else {
                        emptyList()
                    }
                    if (index >= 0) {
                        val current = messages[index] as? ChatMessage.AiMessage
                        messages[index] = (current ?: aiMessage).copy(
                            text = if (displayAction == ContentDisplayAction.SHOW_CANDIDATE) {
                                displayedResponse
                            } else {
                                ""
                            },
                            isGenerating = false,
                            includeInModelContext = responseAccepted,
                            citations = citationSnapshots,
                            ragRunId = ragRunId,
                            ragGenerationStage = null,
                        )
                    }
                    if (displayAction != ContentDisplayAction.SHOW_CANDIDATE) {
                        streamIntoAiMessage(aiMsgId, displayedResponse, aiMessage)
                    }
                    chatAdapter.setGeneratingDone(aiMsgId)
                    chatAdapter.clearActiveAiMessage()
                    submitMessages()
                    isSubmitting = false
                    generationJob = null
                    refreshInputControls()
                    if (index >= 0) {
                        scrollToBottom()
                    }
                }
            }
        }
        return true
    }

    private suspend fun updateRagGenerationStage(
        aiMessageId: Long,
        stage: RagGenerationStage?,
    ) = withContext(Dispatchers.Main.immediate) {
        val index = messages.indexOfFirst { it.id == aiMessageId }
        val current = messages.getOrNull(index) as? ChatMessage.AiMessage ?: return@withContext
        if (!current.isGenerating || current.ragGenerationStage == stage) return@withContext
        messages[index] = current.copy(ragGenerationStage = stage)
        chatAdapter.submitList(messages.toList()) { scrollToBottom() }
    }

    private fun showLocalGuardReply(userMessageText: String, kind: LocalGuardReplyKind) {
        val replyText = getString(
            when (kind) {
                LocalGuardReplyKind.NO_VISUAL_CONTEXT ->
                    R.string.response_blocked_no_visual_context
                LocalGuardReplyKind.UNCERTAIN_VISUAL_REQUEST ->
                    R.string.response_uncertain_visual_request
            }
        )

        showLocalOnlyConversation(userMessageText, replyText)
    }

    private fun showLocalOnlyConversation(
        userMessageText: String,
        replyText: String,
        streamReply: Boolean = true
    ) {
        etInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etInput.windowToken, 0)
        etInput.text = null
        isSubmitting = true
        refreshInputControls()
        collapseAppBar()

        val userMessage = ChatMessage.UserMessage(
            id = conversationStore.nextMessageId(),
            text = userMessageText,
            includeInModelContext = false
        )
        val aiMessageId = conversationStore.nextMessageId()
        val aiMessage = ChatMessage.AiMessage(
            id = aiMessageId,
            text = "",
            isGenerating = true,
            includeInModelContext = false
        )
        messages.add(userMessage)
        messages.add(aiMessage)
        chatAdapter.setActiveAiMessage(aiMessageId)
        submitMessages {
            scrollToBottom()
        }

        localGuardJob = lifecycleScope.launch {
            try {
                if (streamReply) {
                    streamIntoAiMessage(aiMessageId, replyText, aiMessage)
                }
            } finally {
                val index = messages.indexOfFirst { it.id == aiMessageId }
                if (index >= 0) {
                    messages[index] = aiMessage.copy(
                        text = replyText,
                        isGenerating = false
                    )
                }
                chatAdapter.setGeneratingDone(aiMessageId)
                chatAdapter.clearActiveAiMessage()
                submitMessages()
                isSubmitting = false
                localGuardJob = null
                refreshInputControls()
                if (index >= 0) {
                    scrollToBottom()
                }
            }
        }
    }

    private suspend fun streamIntoAiMessage(
        aiMessageId: Long,
        text: String,
        baseMessage: ChatMessage.AiMessage
    ) {
        for (frame in LocalResponseStreamer.frames(text)) {
            val index = messages.indexOfFirst { it.id == aiMessageId }
            if (index >= 0) {
                messages[index] = baseMessage.copy(text = frame, isGenerating = true)
            }
            chatAdapter.updateStreamingText(aiMessageId, frame)
            scrollToBottom()
            delay(LOCAL_GUARD_FRAME_DELAY_MS)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val focusedView = currentFocus
                val barRect = android.graphics.Rect()
                cardInputBar.getGlobalVisibleRect(barRect)
                if (focusedView is TextInputEditText && lastImeBottomInset == 0 &&
                    barRect.contains(ev.rawX.toInt(), ev.rawY.toInt())
                ) {
                    captureImeViewportAnchor()
                }
                pendingImeDismissTap = focusedView is TextInputEditText &&
                    !barRect.contains(ev.rawX.toInt(), ev.rawY.toInt())
                imeDismissDownX = ev.rawX
                imeDismissDownY = ev.rawY
                imeDismissDownTime = ev.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                if (pendingImeDismissTap) {
                    val movedX = kotlin.math.abs(ev.rawX - imeDismissDownX)
                    val movedY = kotlin.math.abs(ev.rawY - imeDismissDownY)
                    if (movedX > imeDismissTouchSlop || movedY > imeDismissTouchSlop) {
                        pendingImeDismissTap = false
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val isShortTap = ev.eventTime - imeDismissDownTime <
                    ViewConfiguration.getLongPressTimeout()
                val focusedView = currentFocus
                if (pendingImeDismissTap && isShortTap && focusedView is TextInputEditText) {
                    focusedView.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
                }
                pendingImeDismissTap = false
            }
            MotionEvent.ACTION_CANCEL -> pendingImeDismissTap = false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        val currentTag = LocaleManager.currentLanguage(this).tag
        if (createdWithLocale != null && createdWithLocale != currentTag) {
            isLocaleRestart = true
            LocaleManager.recreateSeamlessly(this)
            return
        }
        // Re-check: if the model was switched to a TTS model while this
        // activity was in the background, redirect to TtsActivity.
        if (shouldRedirectToTts()) {
            startActivity(Intent(this, TtsActivity::class.java))
            finish()
            return
        }
        if (!::engine.isInitialized) return
        val selectedId = LlamaEngine.getSelectedModel(applicationContext).id

        if (loadedModelId != null && loadedModelId != selectedId) {
            loadedModelId = null
            hasAutoLoaded = false
            reloadAfterModelSwitch()
        } else if (LlamaEngine.consumeModelSwitched(applicationContext)) {
            loadedModelId = selectedId
            clearChatUI()
            updateUIForModelType()
        }
    }

    private fun reloadAfterModelSwitch() {
        if (isClearing) return
        isClearing = true
        isModelReady = false
        refreshInputControls()
        lifecycleScope.launch {
            try {
                pendingImageViewModel.cancelAndClear()
                withContext(Dispatchers.IO) {
                    if (engine.state.value is LlamaState.ModelReady) {
                        engine.unloadModel()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unloading during model switch", e)
            } finally {
                isClearing = false
                clearChatUI()
                loadDefaultModel()
                refreshInputControls()
            }
        }
    }

    override fun onStop() {
        generationJob?.cancel()
        if (conversationStoreDelegate.isInitialized()) persistConversations()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCameraUri?.let { outState.putString(STATE_CAMERA_URI, it.toString()) }
        pendingCameraFile?.let { outState.putString(STATE_CAMERA_FILE_NAME, it.name) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) {
            clearPendingCameraCapture()
        }
        if (isFinishing && !isLocaleRestart && ::engine.isInitialized) {
            engine.destroy()
        }
        if (conversationStoreDelegate.isInitialized()) flushAndCloseConversationWriter()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val CAMERA_CACHE_DIRECTORY = "camera"
        private const val STATE_CAMERA_URI = "pending_camera_uri"
        private const val STATE_CAMERA_FILE_NAME = "pending_camera_file_name"
        private const val LOCAL_GUARD_FRAME_DELAY_MS = 24L
        private const val CONVERSATION_STORE_DIRECTORY = "conversation-store"
        private const val PREVIEW_JPEG_QUALITY = 88
        private const val CONVERSATION_FLUSH_TIMEOUT_SECONDS = 3L
        private const val MAX_EDIT_MESSAGE_CHARACTERS = 250_000
        private const val MAX_CITATION_QUOTE_CHARS = 600
        private const val RAG_RETRIEVAL_VERSION = 1
        private const val RAG_PLANNING_TIMEOUT_MS = 15_000L
        private const val RAG_REVIEW_TIMEOUT_MS = 15_000L
    }
}
