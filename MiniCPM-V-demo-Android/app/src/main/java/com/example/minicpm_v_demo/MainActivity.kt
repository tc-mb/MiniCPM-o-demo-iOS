package com.example.minicpm_v_demo

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.textfield.TextInputEditText
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerChat: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var etInput: TextInputEditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnImage: ImageButton
    private lateinit var btnClearChat: ImageButton
    private lateinit var btnModelManager: ImageButton
    private lateinit var btnImageSlice: ImageButton
    private lateinit var cardInputBar: View
    private lateinit var appBarLayout: AppBarLayout

    private lateinit var engine: LlamaEngine
    private var generationJob: Job? = null
    private var isModelReady = false
    private var isImagePrefilled = false
    private var hasAutoLoaded = false
    private var messageIdCounter = 1L
    private val messages = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            v.updatePadding(
                left = sysBars.left,
                top = sysBars.top,
                right = sysBars.right,
                bottom = maxOf(sysBars.bottom, ime.bottom)
            )
            insets
        }

        LlamaEngine.migrateLegacyLayoutIfNeeded(applicationContext)

        initViews()
        setupRecyclerView()
        setupClickListeners()
        initEngine()
    }

    private fun initViews() {
        recyclerChat = findViewById(R.id.recycler_chat)
        etInput = findViewById(R.id.et_input)
        btnSend = findViewById(R.id.btn_send)
        btnImage = findViewById(R.id.btn_image)
        btnClearChat = findViewById(R.id.btn_clear_chat)
        btnModelManager = findViewById(R.id.btn_model_manager)
        btnImageSlice = findViewById(R.id.btn_image_slice)
        cardInputBar = findViewById(R.id.card_input_bar)
        appBarLayout = findViewById(R.id.appBarLayout)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(Markwon.create(this))
        chatAdapter.setOnStopClick {
            engine.cancelGeneration()
        }
        chatAdapter.setOnSuggestionClick { suggestion ->
            if (isModelReady) {
                etInput.setText(suggestion)
                handleUserInput()
            } else {
                Toast.makeText(this, "请先加载模型", Toast.LENGTH_SHORT).show()
            }
        }

        recyclerChat.layoutManager = LinearLayoutManager(this)
        recyclerChat.adapter = chatAdapter

        cardInputBar.viewTreeObserver.addOnGlobalLayoutListener {
            recyclerChat.setPadding(
                recyclerChat.paddingLeft,
                recyclerChat.paddingTop,
                recyclerChat.paddingRight,
                cardInputBar.height
            )
        }

        messages.add(ChatMessage.WelcomeCard())
        chatAdapter.submitList(messages.toList())
    }

    private fun setupClickListeners() {
        // Pick image OR video.  iOS demo's HXPhotoPicker exposes both
        // photo and video in a single picker; on Android we ask SAF
        // for either MIME, so the user gets the same "pick anything
        // viewable" affordance with no extra "video" button.  Video is
        // only fed to the model if the loaded model is V-4.6 (gated in
        // [handleSelectedMedia] / [LlamaEngine.isVideoUnderstandingSupported]).
        btnImage.setOnClickListener { getMedia.launch(arrayOf("image/*", "video/*")) }
        btnSend.setOnClickListener { handleUserInput() }
        btnClearChat.setOnClickListener { showClearChatDialog() }
        btnModelManager.setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }
        btnImageSlice.setOnClickListener { showImageSliceDialog() }

        etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                collapseAppBar()
                scrollToBottom()
            }
        }
    }

    private fun collapseAppBar() {
        appBarLayout.setExpanded(false, true)
    }

    private fun scrollToBottom() {
        recyclerChat.post {
            val layoutManager = recyclerChat.layoutManager as? LinearLayoutManager ?: return@post
            val lastPos = layoutManager.findLastCompletelyVisibleItemPosition()
            val adapterCount = chatAdapter.itemCount
            if (adapterCount == 0) return@post
            if (lastPos < adapterCount - 2) {
                recyclerChat.scrollToPosition(adapterCount - 1)
            } else {
                recyclerChat.smoothScrollToPosition(adapterCount - 1)
            }
        }
    }

    private fun showClearChatDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_chat)
            .setMessage(R.string.clear_chat_confirm)
            .setPositiveButton("确定") { _, _ ->
                clearChat()
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun clearChat() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine.clearContext()
                // No default system prompt: aligned with iOS opt-r1 (see
                // MBMtmd.mm top-of-file note). The reference Python pipeline
                // (`AutoModel.chat(...)` / `apply_chat_template`) does not
                // insert one either, and an English-only system string biases
                // MiniCPM-V into answering Chinese queries in English.
                // If a caller wants a system prompt, call setSystemPrompt
                // explicitly here before the first user turn.
                withContext(Dispatchers.Main) {
                    messages.clear()
                    messages.add(ChatMessage.WelcomeCard())
                    messageIdCounter = 1L
                    isImagePrefilled = false
                    chatAdapter.submitList(messages.toList())
                    Toast.makeText(this@MainActivity, R.string.clear_chat_toast, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing context", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "清空对话失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initEngine() {
        lifecycleScope.launch(Dispatchers.Default) {
            engine = LlamaEngine.getInstance(applicationContext)
            withContext(Dispatchers.Main) {
                observeEngineState()
            }
        }
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            engine.state.collect { state ->
                when (state) {
                    is LlamaState.Uninitialized,
                    is LlamaState.Initializing -> {
                        enableInput(false)
                    }
                    is LlamaState.Initialized -> {
                        enableInput(false)
                        if (!hasAutoLoaded) {
                            hasAutoLoaded = true
                            loadDefaultModel()
                        }
                    }
                    is LlamaState.LoadingModel -> {
                        enableInput(false)
                    }
                    is LlamaState.ModelReady -> {
                        isModelReady = true
                        enableInput(true)
                        btnImage.isEnabled = engine.isVisionSupported
                    }
                    is LlamaState.ProcessingSystemPrompt,
                    is LlamaState.ProcessingUserPrompt,
                    is LlamaState.Generating -> {
                        enableInput(false)
                    }
                    is LlamaState.PrefillingImage -> {
                        isModelReady = true
                        etInput.isEnabled = true
                        btnSend.isEnabled = true
                        btnImage.isEnabled = false
                    }
                    is LlamaState.UnloadingModel -> {
                        enableInput(false)
                    }
                    is LlamaState.Error -> {
                        enableInput(false)
                    }
                }
            }
        }
    }

    private fun enableInput(enable: Boolean) {
        etInput.isEnabled = enable
        btnSend.isEnabled = enable
        if (!enable) {
            btnImage.isEnabled = false
        } else {
            btnImage.isEnabled = engine.isVisionSupported
        }
    }

    private fun loadDefaultModel() {
        val ctx = applicationContext
        val ggufFile = File(LlamaEngine.modelPath(ctx))
        val mmprojFile = File(LlamaEngine.mmprojPath(ctx))

        // Both files must be on-disk before we even try to load. Falling back
        // to a text-only load when mmproj is missing is the wrong default for
        // this demo: vision is the marquee feature, and silently disabling
        // the image button leaves the user wondering why "新装的 apk 点不开
        // 图片". Common trigger is `migrateLegacyLayoutIfNeeded` having just
        // purged a stale mmproj after an APK upgrade - in that case the user
        // needs to re-download from "模型管理".
        if (!ggufFile.exists() || !mmprojFile.exists()) {
            promptDownloadModels(
                ggufMissing = !ggufFile.exists(),
                mmprojMissing = !mmprojFile.exists()
            )
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine.loadModel(ggufFile.absolutePath, mmprojFile.absolutePath)
                // No default system prompt: aligned with iOS opt-r1. See
                // clearChat() above for the rationale.
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                engine.resetToInitialized()
                hasAutoLoaded = false
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "模型加载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun promptDownloadModels(ggufMissing: Boolean, mmprojMissing: Boolean) {
        val message = when {
            ggufMissing && mmprojMissing ->
                "未检测到模型文件。请前往“模型管理”下载后再使用。"
            mmprojMissing ->
                "本次升级更新了图像模型（mmproj）。\n请前往“模型管理”重新下载，否则无法使用图片识别功能。"
            else ->
                "模型文件不完整。请前往“模型管理”重新下载。"
        }
        AlertDialog.Builder(this)
            .setTitle("需要下载模型")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("去下载") { _, _ ->
                startActivity(Intent(this, ModelManagerActivity::class.java))
            }
            .setNegativeButton("稍后") { _, _ ->
                Toast.makeText(
                    this,
                    "可随时点击右上角“模型管理”按钮下载",
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

    private fun handleSelectedMedia(uri: Uri) {
        if (!isModelReady) {
            Toast.makeText(this, "请先加载模型", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = contentResolver.getType(uri).orEmpty()
        when {
            mime.startsWith("video/") -> handleSelectedVideo(uri)
            mime.startsWith("image/") || mime.isEmpty() -> handleSelectedImage(uri)
            else -> {
                Toast.makeText(this, "不支持的文件类型: $mime", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleSelectedImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imageData = contentResolver.openInputStream(uri)?.use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                        ?: throw RuntimeException("无法解码图片")
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    Pair(stream.toByteArray(), bitmap)
                } ?: throw RuntimeException("无法读取图片")

                val (imageBytes, bitmap) = imageData

                val imageName = getFileName(uri)
                val width = bitmap.width
                val height = bitmap.height
                val sizeKb = imageBytes.size / 1024
                val imageInfo = "$width x $height ($sizeKb KB)"
                val msgId = messageIdCounter++

                withContext(Dispatchers.Main) {
                    val imageMessage = ChatMessage.UserMessage(
                        id = msgId,
                        text = "",
                        imageBitmap = bitmap,
                        imageInfo = imageInfo,
                        isPrefilling = true
                    )
                    messages.add(imageMessage)
                    chatAdapter.submitList(messages.toList()) {
                        scrollToBottom()
                    }
                }

                engine.prefillImage(imageBytes)

                isImagePrefilled = true

                withContext(Dispatchers.Main) {
                    val index = messages.indexOfFirst { it.id == msgId }
                    if (index >= 0) {
                        messages[index] = (messages[index] as ChatMessage.UserMessage).copy(
                            isPrefilling = false
                        )
                        chatAdapter.submitList(messages.toList())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "处理图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
                "视频理解仅在 MiniCPM-V-4.6 上可用，请前往“模型管理”切换模型",
                Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val msgId = messageIdCounter++
            val startNs = System.nanoTime()
            try {
                val extracted = VideoFrameExtractor.extract(applicationContext, uri)
                val info = VideoFrameExtractor.formatVideoInfo(extracted)
                Log.i(TAG, "Video info: $info")

                withContext(Dispatchers.Main) {
                    val videoMessage = ChatMessage.UserMessage(
                        id = msgId,
                        text = "",
                        imageBitmap = extracted.thumbnail,
                        imageInfo = info,
                        isPrefilling = true,
                        isVideo = true
                    )
                    messages.add(videoMessage)
                    chatAdapter.submitList(messages.toList()) {
                        scrollToBottom()
                    }
                }

                engine.prefillVideoFrames(extracted.frames) { current, total ->
                    withContext(Dispatchers.Main) {
                        val index = messages.indexOfFirst { it.id == msgId }
                        if (index >= 0) {
                            val cur = messages[index] as ChatMessage.UserMessage
                            messages[index] = cur.copy(
                                imageInfo = "$info · 处理中 $current/$total"
                            )
                            chatAdapter.submitList(messages.toList())
                        }
                    }
                }

                isImagePrefilled = true

                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                withContext(Dispatchers.Main) {
                    val index = messages.indexOfFirst { it.id == msgId }
                    if (index >= 0) {
                        val cur = messages[index] as ChatMessage.UserMessage
                        messages[index] = cur.copy(
                            imageInfo = "$info · 预处理 ${elapsedMs / 1000.0}s",
                            isPrefilling = false
                        )
                        chatAdapter.submitList(messages.toList())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing video", e)
                withContext(Dispatchers.Main) {
                    val index = messages.indexOfFirst { it.id == msgId }
                    if (index >= 0) {
                        messages.removeAt(index)
                        chatAdapter.submitList(messages.toList())
                    }
                    Toast.makeText(this@MainActivity, "处理视频失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return "file-${System.currentTimeMillis()}"
    }

    private fun handleUserInput() {
        val userMsg = etInput.text.toString().trim()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, "请输入文字消息", Toast.LENGTH_SHORT).show()
            return
        }

        etInput.text = null
        enableInput(false)

        collapseAppBar()

        val msgId = messageIdCounter++
        val userMessage = ChatMessage.UserMessage(
            id = msgId,
            text = userMsg,
            imageBitmap = null,
            imageInfo = null
        )
        messages.add(userMessage)
        chatAdapter.submitList(messages.toList()) {
            scrollToBottom()
        }

        isImagePrefilled = false

        val aiMsgId = messageIdCounter++
        val aiMessage = ChatMessage.AiMessage(id = aiMsgId, text = "", isGenerating = true)
        messages.add(aiMessage)
        chatAdapter.setActiveAiMessage(aiMsgId)
        chatAdapter.submitList(messages.toList()) {
            scrollToBottom()
        }

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            val fullResponse = StringBuilder()
            engine.sendUserPrompt(userMsg)
                .onCompletion {
                    withContext(Dispatchers.Main) {
                        val index = messages.indexOfFirst { it.id == aiMsgId }
                        if (index >= 0) {
                            messages[index] = (messages[index] as ChatMessage.AiMessage).copy(
                                text = fullResponse.toString(),
                                isGenerating = false
                            )
                        }
                        chatAdapter.setGeneratingDone(aiMsgId)
                        chatAdapter.clearActiveAiMessage()
                        chatAdapter.submitList(messages.toList())
                        enableInput(true)
                        scrollToBottom()
                    }
                }
                .collect { token ->
                    fullResponse.append(token)
                    withContext(Dispatchers.Main) {
                        val currentText = fullResponse.toString()
                        val index = messages.indexOfFirst { it.id == aiMsgId }
                        if (index >= 0) {
                            messages[index] = ChatMessage.AiMessage(
                                id = aiMsgId,
                                text = currentText,
                                isGenerating = true
                            )
                        }
                        chatAdapter.updateStreamingText(aiMsgId, currentText)
                        scrollToBottom()
                    }
                }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is TextInputEditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) {
            engine.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }
}
