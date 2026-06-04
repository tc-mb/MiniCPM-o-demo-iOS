package com.example.minicpm_v_demo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

sealed class TtsState {
    object Uninitialized : TtsState()
    object Initializing : TtsState()
    object LoadingModel : TtsState()
    object Ready : TtsState()
    object Generating : TtsState()
    data class Error(val exception: Exception) : TtsState()
}

class TtsEngine private constructor(
    private val context: Context
) {
    companion object {
        private val TAG = TtsEngine::class.java.simpleName

        @Volatile
        private var instance: TtsEngine? = null

        fun getInstance(context: Context): TtsEngine =
            instance ?: synchronized(this) {
                TtsEngine(context).also { instance = it }
            }
    }

    private val ttsDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val ttsScope = CoroutineScope(ttsDispatcher + SupervisorJob())

    private val _state = MutableStateFlow<TtsState>(TtsState.Uninitialized)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    @Volatile
    var isLoaded = false
        private set

    @Volatile
    var sampleRate: Int = 48000
        private set

    private external fun nativeInitOmni(baseLmPath: String, acousticPath: String): Boolean
    private external fun nativeTtsGenerate(
        text: String, cfgValue: Float, timesteps: Int,
        refWavPath: String, outputPath: String
    ): Boolean
    private external fun nativeOmniFree()

    init {
        ttsScope.launch {
            try {
                check(_state.value is TtsState.Uninitialized)
                _state.value = TtsState.Initializing
                Log.i(TAG, "Loading native library for TTS...")
                System.loadLibrary("minicpm_v_demo") // omni_jni is compiled into the same shared lib
                Log.i(TAG, "TTS native library loaded")
                // Don't auto-init; wait for loadModel to be called
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                _state.value = TtsState.Error(e)
                throw e
            }
        }
    }

    suspend fun loadModel(): Boolean = withContext(ttsDispatcher) {
        val model = LlamaEngine.getSelectedModel(context)
        val baseLmPath = LlamaEngine.modelPath(context)
        val acousticPath = LlamaEngine.acousticPath(context) ?: run {
            val e = RuntimeException("Acoustic GGUF not configured for model ${model.id}")
            _state.value = TtsState.Error(e)
            return@withContext false
        }

        check(File(baseLmPath).exists()) { "BaseLM GGUF not found: $baseLmPath" }
        check(File(acousticPath).exists()) { "Acoustic GGUF not found: $acousticPath" }

        try {
            _state.value = TtsState.LoadingModel
            Log.i(TAG, "Initializing VoxCPM2 runtime: baseLm=$baseLmPath acoustic=$acousticPath")
            val ok = nativeInitOmni(baseLmPath, acousticPath)
            if (!ok) {
                val e = RuntimeException("VoxCPM2 runtime init failed")
                _state.value = TtsState.Error(e)
                return@withContext false
            }
            isLoaded = true
            sampleRate = 48000 // VoxCPM2 native output
            _state.value = TtsState.Ready
            Log.i(TAG, "VoxCPM2 runtime ready")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading VoxCPM2 model", e)
            _state.value = TtsState.Error(e)
            return@withContext false
        }
    }

    suspend fun generate(
        text: String,
        cfgValue: Float = 2.0f,
        timesteps: Int = 10,
        referenceWavPath: String? = null,
        outputPath: String
    ): Boolean = withContext(ttsDispatcher) {
        check(isLoaded) { "TTS engine not loaded" }
        check(text.isNotBlank()) { "Text must not be empty" }
        check(_state.value is TtsState.Ready) { "Engine not ready: ${_state.value}" }

        try {
            _state.value = TtsState.Generating
            Log.i(TAG, "Generating speech: len=${text.length} cfg=$cfgValue steps=$timesteps refAudio=${referenceWavPath != null}")

            val ok = nativeTtsGenerate(
                text,
                cfgValue,
                timesteps,
                referenceWavPath ?: "",
                outputPath
            )

            if (!ok) {
                val e = RuntimeException("TTS generation failed")
                _state.value = TtsState.Error(e)
                return@withContext false
            }

            Log.i(TAG, "Speech generated: $outputPath (${File(outputPath).length() / 1024} KB)")
            _state.value = TtsState.Ready
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error during TTS generation", e)
            _state.value = TtsState.Error(e)
            throw e
        }
    }

    fun destroy() {
        ttsScope.launch {
            if (isLoaded) {
                nativeOmniFree()
                isLoaded = false
                Log.i(TAG, "TTS engine destroyed")
            }
        }
        ttsScope.cancel()
    }
}
