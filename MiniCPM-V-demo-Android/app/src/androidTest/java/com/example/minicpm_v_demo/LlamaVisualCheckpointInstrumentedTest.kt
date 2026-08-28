package com.example.minicpm_v_demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaVisualCheckpointInstrumentedTest {
    @Test
    fun restoringCheckpointPreservesRealPrefilledImageState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(CheckpointTestHostActivity::class.java).use {
            runVisualCheckpointTest(context)
        }
    }

    private suspend fun runVisualCheckpointTest(context: Context) {
        val preferences = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
        val originalSliceCount = preferences.getInt(
            "image_max_slice_nums",
            LlamaEngine.DEFAULT_IMAGE_SLICE,
        )
        var engine: LlamaEngine? = null
        check(preferences.edit().putInt("image_max_slice_nums", 1).commit())
        logStage("slice_preference_applied")

        try {
            engine = readyFreshEngine(context)
            logStage("model_and_mmproj_ready")
            check(engine.isVisionSupported) { "Production vision projector is not installed" }
            assertEquals(8192, engine.nativeContextDebugSnapshot().contextCapacity)
            logStage("visual_context_ready")

            val prefillStart = SystemClock.elapsedRealtimeNanos()
            engine.prefillImage(createTestImage())
            val prefillMs = (SystemClock.elapsedRealtimeNanos() - prefillStart) / 1_000_000.0
            Log.i(TEST_TAG, "stage=image_prefilled prefillMs=$prefillMs")

            val stable = engine.nativeContextDebugSnapshot()
            assertTrue(stable.imagePrefilled)
            assertTrue(stable.visionMode)

            val firstCheckpoint = engine.beginEphemeralTurn()
            val firstToken = engine.sendUserPrompt("Name the dominant color in one word.", 8)
                .take(1)
                .toList()
                .single()
            engine.restoreEphemeralTurn(firstCheckpoint)
            assertEquals(stable, engine.nativeContextDebugSnapshot())
            logStage("first_branch_restored")

            val secondCheckpoint = engine.beginEphemeralTurn()
            val repeatedFirstToken = engine.sendUserPrompt("Name the dominant color in one word.", 8)
                .take(1)
                .toList()
                .single()
            engine.restoreEphemeralTurn(secondCheckpoint)

            assertEquals(firstToken, repeatedFirstToken)
            assertEquals(stable, engine.nativeContextDebugSnapshot())
            logStage("checkpoint_verified")
        } finally {
            check(preferences.edit().putInt("image_max_slice_nums", originalSliceCount).commit())
            if (engine?.state?.value is LlamaState.ModelReady) {
                engine.unloadModel()
            }
        }
    }

    private suspend fun readyFreshEngine(context: Context): LlamaEngine {
        val engine = LlamaEngine.getInstance(context)
        val initializedState = withTimeout(30_000) {
            engine.state.first { state: LlamaState ->
                state is LlamaState.Initialized || state is LlamaState.ModelReady || state is LlamaState.Error
            }
        }
        check(initializedState !is LlamaState.Error) { "Native initialization failed" }
        if (initializedState is LlamaState.ModelReady) {
            engine.unloadModel()
            logStage("previous_model_unloaded")
        }
        check(engine.state.value is LlamaState.Initialized) {
            "Engine must be initialized before the deterministic model load"
        }

        val model = File(LlamaEngine.modelPath(context))
        check(model.isFile) { "Production model is not installed: ${model.absolutePath}" }
        val mmproj = LlamaEngine.mmprojPath(context)?.let(::File)?.takeIf(File::isFile)
        logStage("model_load_started")
        withTimeout(180_000) {
            engine.loadModel(model.absolutePath, mmproj?.absolutePath)
        }
        return engine
    }

    private fun logStage(stage: String) {
        Log.i(TEST_TAG, "stage=$stage elapsedMs=${SystemClock.elapsedRealtime()}")
    }

    private fun createTestImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, if (x < 72) Color.BLUE else Color.YELLOW)
            }
        }
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private companion object {
        const val TEST_TAG = "VISUAL_CHECKPOINT"
    }
}
