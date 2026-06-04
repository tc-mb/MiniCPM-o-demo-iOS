package com.example.minicpm_v_demo

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TtsActivity : AppCompatActivity() {

    companion object {
        private val TAG = TtsActivity::class.java.simpleName
        private const val REQUEST_RECORD_AUDIO = 1001
    }

    private lateinit var etText: TextInputEditText
    private lateinit var btnRecord: MaterialButton
    private lateinit var btnPlayRef: MaterialButton
    private lateinit var btnClearRef: MaterialButton
    private lateinit var tvRefInfo: TextView
    private lateinit var sliderCfg: Slider
    private lateinit var tvCfgValue: TextView
    private lateinit var sliderTimesteps: Slider
    private lateinit var tvTimestepsValue: TextView
    private lateinit var tvTimestepsHint: TextView
    private lateinit var btnGenerate: MaterialButton
    private lateinit var progressGenerate: LinearProgressIndicator
    private lateinit var tvStatus: TextView
    private lateinit var cardPlayback: View
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var tvPlayTime: TextView
    private lateinit var sliderPlayback: Slider

    private lateinit var btnPresetFemale: MaterialButton
    private lateinit var btnPresetMale: MaterialButton

    private lateinit var engine: TtsEngine
    private lateinit var recorder: AudioRecorder
    private var referenceWavFile: File? = null
    private var isGenerating = false
    private var generationJob: Job? = null
    private var createdWithLocale: String? = null

    // Playback
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var generatedWavPath: String? = null
    private var playbackHandler = Handler(Looper.getMainLooper())
    private var playbackRunnable: Runnable? = null
    private var generatedDurationMs = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdWithLocale = LocaleManager.currentLanguage(this).tag
        setContentView(R.layout.activity_tts)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = 0, top = 0,
                right = 0, bottom = ime.bottom
            )
            insets
        }

        initViews()
        setupListeners()
        initEngine()
    }

    private fun initViews() {
        etText = findViewById(R.id.et_tts_text)
        btnRecord = findViewById(R.id.btn_record)
        btnPlayRef = findViewById(R.id.btn_play_ref)
        btnClearRef = findViewById(R.id.btn_clear_ref)
        btnPresetFemale = findViewById(R.id.btn_preset_female)
        btnPresetMale = findViewById(R.id.btn_preset_male)
        tvRefInfo = findViewById(R.id.tv_ref_audio_info)
        sliderCfg = findViewById(R.id.slider_cfg)
        tvCfgValue = findViewById(R.id.tv_cfg_value)
        sliderTimesteps = findViewById(R.id.slider_timesteps)
        tvTimestepsValue = findViewById(R.id.tv_timesteps_value)
        tvTimestepsHint = findViewById(R.id.tv_timesteps_hint)
        btnGenerate = findViewById(R.id.btn_generate)
        progressGenerate = findViewById(R.id.progress_generate)
        tvStatus = findViewById(R.id.tv_status)
        cardPlayback = findViewById(R.id.card_playback)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        tvPlayTime = findViewById(R.id.tv_play_time)
        sliderPlayback = findViewById(R.id.slider_playback)

        findViewById<ImageButton>(R.id.btn_model_manager).setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }
    }

    private fun setupListeners() {
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        sliderCfg.addOnChangeListener { _, value, _ ->
            tvCfgValue.text = "%.1f".format(value)
        }
        sliderTimesteps.addOnChangeListener { _, value, _ ->
            val steps = value.toInt()
            tvTimestepsValue.text = steps.toString()
            tvTimestepsHint.visibility = if (steps > 8) View.VISIBLE else View.GONE
        }

        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        btnPlayRef.setOnClickListener {
            referenceWavFile?.let { playWavFile(it.absolutePath) }
        }

        btnClearRef.setOnClickListener {
            referenceWavFile?.delete()
            referenceWavFile = null
            updateRefAudioUI()
        }

        btnPresetFemale.setOnClickListener { selectPresetRefAudio("默认女声.wav") }
        btnPresetMale.setOnClickListener { selectPresetRefAudio("默认男声.wav") }

        btnGenerate.setOnClickListener {
            if (isGenerating) {
                cancelGeneration()
            } else {
                doGenerate()
            }
        }

        btnPlayPause.setOnClickListener {
            if (isPlaying) pausePlayback() else resumeOrStartPlayback()
        }

        sliderPlayback.addOnChangeListener { _, value, _ ->
            if (generatedDurationMs > 0) {
                val seekMs = (value / 100f * generatedDurationMs).toInt()
                // Seek not implemented for simplicity; just update time display
                val min = seekMs / 60000
                val sec = (seekMs % 60000) / 1000
                tvPlayTime.text = String.format("%d:%02d / %d:%02d",
                    min, sec,
                    generatedDurationMs / 60000,
                    (generatedDurationMs % 60000) / 1000)
            }
        }
    }

    private fun initEngine() {
        recorder = AudioRecorder(this)
        lifecycleScope.launch(Dispatchers.Default) {
            engine = TtsEngine.getInstance(this@TtsActivity)
            withContext(Dispatchers.Main) {
                observeEngineState()
                // Wait for Initializing to finish (native lib loaded), then load model
                loadTtsModels()
            }
        }
    }

    private fun loadTtsModels() {
        lifecycleScope.launch {
            try {
                // Brief delay for async init (native lib loading) to complete
                delay(300)

                val currentState = engine.state.value
                if (currentState is TtsState.Error) {
                    Toast.makeText(this@TtsActivity,
                        getString(R.string.tts_error, (currentState as TtsState.Error).exception.message),
                        Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Check if model files exist
                val ctx = applicationContext
                val model = LlamaEngine.getSelectedModel(ctx)
                val baseLmFile = File(LlamaEngine.modelPath(ctx))
                val acousticFile = File(LlamaEngine.acousticPath(ctx) ?: "")
                val baseLmMissing = !baseLmFile.exists()
                val acousticMissing = model.acousticFileName != null && !acousticFile.exists()

                if (baseLmMissing || acousticMissing) {
                    promptDownloadModels(baseLmMissing, acousticMissing)
                    return@launch
                }

                // Load model on IO thread
                withContext(Dispatchers.Default) {
                    val ok = engine.loadModel()
                    withContext(Dispatchers.Main) {
                        if (!ok) {
                            Toast.makeText(this@TtsActivity,
                                R.string.tts_model_load_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadTtsModels", e)
            }
        }
    }

    private fun promptDownloadModels(baseLmMissing: Boolean, acousticMissing: Boolean) {
        val message = when {
            baseLmMissing && acousticMissing ->
                getString(R.string.download_prompt_all_missing)
            baseLmMissing ->
                getString(R.string.tts_baselm_missing)
            else ->
                getString(R.string.tts_acoustic_missing)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tts_download_prompt_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.go_download) { _, _ ->
                startActivity(Intent(this, ModelManagerActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                Toast.makeText(this, R.string.tts_download_prompt_message, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            engine.state.collect { state ->
                when (state) {
                    is TtsState.Uninitialized, is TtsState.Initializing -> {
                        btnGenerate.isEnabled = false
                    }
                    is TtsState.LoadingModel -> {
                        btnGenerate.isEnabled = false
                        progressGenerate.visibility = View.VISIBLE
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.setText(R.string.tts_loading_model)
                    }
                    is TtsState.Ready -> {
                        isGenerating = false
                        btnGenerate.isEnabled = true
                        btnGenerate.setText(R.string.tts_generate)
                        progressGenerate.visibility = View.GONE
                        tvStatus.visibility = View.GONE
                    }
                    is TtsState.Generating -> {
                        isGenerating = true
                        btnGenerate.isEnabled = true
                        btnGenerate.setText(R.string.tts_cancel)
                        progressGenerate.visibility = View.VISIBLE
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.setText(R.string.tts_generating)
                    }
                    is TtsState.Error -> {
                        isGenerating = false
                        btnGenerate.isEnabled = true
                        btnGenerate.setText(R.string.tts_generate)
                        progressGenerate.visibility = View.GONE
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = getString(R.string.tts_error, state.exception.message)
                    }
                }
            }
        }
    }

    private var isRecording = false

    private fun startRecording() {
        if (!recorder.hasPermission) {
            requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            return
        }
        referenceWavFile?.delete()
        referenceWavFile = File(cacheDir, "ref_audio_${System.currentTimeMillis()}.wav")
        if (recorder.startRecording(referenceWavFile!!)) {
            isRecording = true
            btnRecord.text = getString(R.string.tts_stop_record)
            btnRecord.setIconResource(R.drawable.ic_pause)
            tvRefInfo.visibility = View.VISIBLE
            tvRefInfo.setText(R.string.tts_recording)
            btnClearRef.visibility = View.GONE
            btnPlayRef.visibility = View.GONE
        }
    }

    private fun stopRecording() {
        val size = recorder.stopRecording()
        isRecording = false
        btnRecord.text = getString(R.string.tts_record)
        btnRecord.setIconResource(R.drawable.ic_play_arrow)

        if (referenceWavFile != null && referenceWavFile!!.exists() && referenceWavFile!!.length() > 0) {
            val durMs = recorder.getDurationMs(referenceWavFile!!)
            tvRefInfo.visibility = View.VISIBLE
            tvRefInfo.text = getString(R.string.tts_ref_recorded, durMs / 1000.0,
                referenceWavFile!!.length() / 1024)
            btnClearRef.visibility = View.VISIBLE
            btnPlayRef.visibility = View.VISIBLE
        } else {
            tvRefInfo.visibility = View.GONE
            referenceWavFile = null
        }
        updateRefAudioUI()
    }

    private fun updateRefAudioUI() {
        val hasRef = referenceWavFile != null && referenceWavFile!!.exists()
        btnClearRef.visibility = if (hasRef) View.VISIBLE else View.GONE
        btnPlayRef.visibility = if (hasRef) View.VISIBLE else View.GONE
        if (!hasRef) {
            tvRefInfo.visibility = View.GONE
        }
    }

    private fun selectPresetRefAudio(assetName: String) {
        // Stop recording if active
        if (isRecording) stopRecording()

        // Clear previous reference
        referenceWavFile?.delete()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val destFile = File(cacheDir, "preset_$assetName")
                destFile.parentFile?.mkdirs()

                // Copy from assets
                assets.open("ref_audios/$assetName").use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val durMs = recorder.getDurationMs(destFile)
                withContext(Dispatchers.Main) {
                    referenceWavFile = destFile
                    tvRefInfo.visibility = View.VISIBLE
                    tvRefInfo.text = getString(R.string.tts_ref_recorded,
                        durMs / 1000.0, destFile.length() / 1024)
                    btnClearRef.visibility = View.VISIBLE
                    btnPlayRef.visibility = View.VISIBLE

                    // Highlight the selected preset (filename always uses Chinese characters)
                    val isFemale = assetName == "默认女声.wav"
                    btnPresetFemale.isChecked = isFemale
                    btnPresetMale.isChecked = !isFemale
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy preset: $assetName", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TtsActivity,
                        getString(R.string.tts_preset_load_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun doGenerate() {
        val text = etText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.tts_text_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val outputDir = File(cacheDir, "tts_output")
        outputDir.mkdirs()
        val outputFile = File(outputDir, "tts_${System.currentTimeMillis()}.wav")
        generatedWavPath = outputFile.absolutePath

        val cfg = sliderCfg.value
        val steps = sliderTimesteps.value.toInt()
        val refPath = referenceWavFile?.absolutePath

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val ok = engine.generate(text, cfg, steps, refPath, outputFile.absolutePath)
                withContext(Dispatchers.Main) {
                    onGenerationComplete(ok, outputFile)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User cancelled; engine state will be reset by cancelGeneration
                withContext(Dispatchers.Main) {
                    progressGenerate.visibility = View.GONE
                    tvStatus.setText(R.string.tts_cancelled)
                }
            }
        }
    }

    private fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        isGenerating = false
        btnGenerate.setText(R.string.tts_generate)
        progressGenerate.visibility = View.GONE
        tvStatus.text = getString(R.string.tts_cancelled)
        Toast.makeText(this, R.string.tts_cancelled, Toast.LENGTH_SHORT).show()
    }

    private fun onGenerationComplete(ok: Boolean, outputFile: File) {
        generationJob = null
        isGenerating = false
        btnGenerate.setText(R.string.tts_generate)
        progressGenerate.visibility = View.GONE
        if (ok && outputFile.exists() && outputFile.length() > 0) {
            tvStatus.visibility = View.GONE
            cardPlayback.visibility = View.VISIBLE
            generatedDurationMs = getWavDurationMs(outputFile)
            sliderPlayback.value = 0f
            tvPlayTime.text = String.format("0:00 / %d:%02d",
                generatedDurationMs / 60000,
                (generatedDurationMs % 60000) / 1000)
            Toast.makeText(this, R.string.tts_generate_done, Toast.LENGTH_SHORT).show()
        } else {
            tvStatus.setText(R.string.tts_generate_failed)
            Toast.makeText(this, R.string.tts_generate_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun resumeOrStartPlayback() {
        if (isPlaying) return
        generatedWavPath?.let { playWavFile(it) }
    }

    private fun playWavFile(path: String) {
        stopPlayback()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(path)
                val raf = java.io.RandomAccessFile(file, "r")

                // Parse WAV header
                val header = ByteArray(44)
                raf.readFully(header)
                val sampleRate = ((header[27].toInt() and 0xFF) shl 24) or
                                 ((header[26].toInt() and 0xFF) shl 16) or
                                 ((header[25].toInt() and 0xFF) shl 8) or
                                 (header[24].toInt() and 0xFF)
                val numChannels = ((header[23].toInt() and 0xFF) shl 8) or
                                  (header[22].toInt() and 0xFF)
                val bitsPerSample = ((header[35].toInt() and 0xFF) shl 8) or
                                    (header[34].toInt() and 0xFF)

                // Find "data" chunk — read more of the file header if needed
                val headerSize = minOf(file.length(), 4096).toInt()
                val headerBuf = ByteArray(headerSize)
                raf.seek(0)
                raf.readFully(headerBuf)
                var dataOffset = 36
                var found = false
                while (dataOffset + 8 <= headerSize) {
                    val chunkId = String(headerBuf, dataOffset, 4)
                    val chunkSize = ((headerBuf[dataOffset + 7].toInt() and 0xFF) shl 24) or
                                    ((headerBuf[dataOffset + 6].toInt() and 0xFF) shl 16) or
                                    ((headerBuf[dataOffset + 5].toInt() and 0xFF) shl 8) or
                                    (headerBuf[dataOffset + 4].toInt() and 0xFF)
                    if (chunkId == "data") {
                        dataOffset += 8
                        found = true
                        break
                    }
                    dataOffset += 8 + chunkSize
                    if (dataOffset < 0 || dataOffset >= headerSize) break
                }
                if (!found) {
                    Log.e(TAG, "No data chunk found in WAV: $path")
                    raf.close()
                    return@launch
                }

                val dataSize = (file.length() - dataOffset).toInt()
                if (dataSize <= 0) { raf.close(); return@launch }
                raf.seek(dataOffset.toLong())

                val channelMask = if (numChannels == 1)
                    AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

                val audioEncoding = if (bitsPerSample == 16)
                    AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate, channelMask, audioEncoding
                )
                val playBufferSize = maxOf(bufferSize, 4096)

                audioTrack = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(audioEncoding)
                        .build(),
                    playBufferSize,
                    AudioTrack.MODE_STREAM,
                    0
                )

                if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack init failed: sr=$sampleRate ch=$numChannels bits=$bitsPerSample")
                    stopPlayback()
                    raf.close()
                    return@launch
                }

                val bytesPerSample = bitsPerSample / 8
                val bytesPerSecond = sampleRate * numChannels * bytesPerSample
                val durationMs = if (bytesPerSecond > 0) (dataSize.toLong() * 1000 / bytesPerSecond).toInt() else 0
                generatedDurationMs = durationMs

                withContext(Dispatchers.Main) {
                    isPlaying = true
                    btnPlayPause.setIconResource(R.drawable.ic_pause)
                    sliderPlayback.value = 0f
                    val dmin = durationMs / 60000
                    val dsec = (durationMs % 60000) / 1000
                    tvPlayTime.text = String.format("0:00 / %d:%02d", dmin, dsec)
                }

                audioTrack?.play()

                // Write data in chunks (MODE_STREAM)
                val chunk = ByteArray(playBufferSize)
                var totalWritten = 0
                val startMs = System.currentTimeMillis()
                var bytesRead: Int
                while (raf.read(chunk).also { bytesRead = it } > 0) {
                    audioTrack?.write(chunk, 0, bytesRead)
                    totalWritten += bytesRead
                    // Update progress every ~200ms
                    if (totalWritten % (playBufferSize * 2) == 0 || totalWritten >= dataSize) {
                        val elapsedMs = (System.currentTimeMillis() - startMs).toInt()
                        val progress = if (durationMs > 0)
                            (elapsedMs.toFloat() / durationMs * 100f).coerceAtMost(100f)
                        else 0f
                        withContext(Dispatchers.Main) {
                            sliderPlayback.value = progress
                            val emin = elapsedMs / 60000
                            val esec = (elapsedMs % 60000) / 1000
                            val dmin = durationMs / 60000
                            val dsec = (durationMs % 60000) / 1000
                            tvPlayTime.text = String.format("%d:%02d / %d:%02d", emin, esec, dmin, dsec)
                        }
                    }
                }
                raf.close()

                // Wait for playback to finish
                val totalDuration = durationMs.toLong()
                playbackHandler.postDelayed({
                    stopPlayback()
                }, maxOf(totalDuration, 500))
            } catch (e: Exception) {
                Log.e(TAG, "playWavFile failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TtsActivity, getString(R.string.tts_play_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun pausePlayback() {
        try {
            audioTrack?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) it.pause()
            }
        } catch (e: Exception) {
            Log.w(TAG, "pausePlayback error", e)
        }
        isPlaying = false
        playbackRunnable?.let { playbackHandler.removeCallbacks(it) }
        btnPlayPause.setIconResource(R.drawable.ic_play_arrow)
    }

    private fun stopPlayback() {
        try {
            audioTrack?.let {
                if (it.state != AudioTrack.STATE_UNINITIALIZED) {
                    it.pause()
                    it.flush()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopPlayback error", e)
        } finally {
            audioTrack = null
            isPlaying = false
            playbackRunnable?.let { playbackHandler.removeCallbacks(it) }
            btnPlayPause.setIconResource(R.drawable.ic_play_arrow)
        }
    }

    private fun getWavDurationMs(file: File): Int {
        return try {
            val raf = java.io.RandomAccessFile(file, "r")
            val header = ByteArray(44)
            raf.readFully(header)
            raf.close()
            val sampleRate = ((header[27].toInt() and 0xFF) shl 24) or
                             ((header[26].toInt() and 0xFF) shl 16) or
                             ((header[25].toInt() and 0xFF) shl 8) or
                             (header[24].toInt() and 0xFF)
            val numChannels = ((header[23].toInt() and 0xFF) shl 8) or
                              (header[22].toInt() and 0xFF)
            val dataSize = file.length() - 44
            if (dataSize <= 0 || sampleRate <= 0) 0
            else ((dataSize * 1000) / (sampleRate * numChannels * 2)).toInt()
        } catch (e: Exception) {
            0
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(this, R.string.tts_record_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If user switched language in ModelManagerActivity, restart seamlessly.
        val currentTag = LocaleManager.currentLanguage(this).tag
        if (createdWithLocale != null && createdWithLocale != currentTag) {
            LocaleManager.recreateSeamlessly(this)
            return
        }
        // If user switched to a non-TTS model from ModelManagerActivity,
        // redirect back to MainActivity.
        if (!LlamaEngine.getSelectedModel(applicationContext).isTts) {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
    }

    override fun onDestroy() {
        stopPlayback()
        if (isFinishing && ::engine.isInitialized) {
            engine.destroy()
        }
        super.onDestroy()
    }
}
