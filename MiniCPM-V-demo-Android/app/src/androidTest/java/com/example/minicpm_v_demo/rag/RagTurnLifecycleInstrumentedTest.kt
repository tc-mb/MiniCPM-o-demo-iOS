package com.example.minicpm_v_demo.rag

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagTurnLifecycleInstrumentedTest {
    @Test
    fun twentyBackgroundCyclesCancelActiveRagCheckpoint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        bringDebugHostToForeground(context)
        val engine = readyTextEngine(context)
        launchMainActivity(context)

        repeat(BACKGROUND_CYCLES) { cycle ->
            val activity = awaitResumedMainActivity()
            val checkpointReady = CompletableDeferred<Unit>()
            val generation = activity.lifecycleScope.launch(
                context = Dispatchers.Default,
                start = CoroutineStart.LAZY,
            ) {
                val transaction = RagTurnTransaction(engine, engine.beginEphemeralTurn())
                checkpointReady.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    transaction.rollback(
                        keepUserInHistory = false,
                        originalUserText = "lifecycle probe $cycle",
                    )
                }
            }
            installGenerationJob(activity, generation)
            generation.start()

            withTimeout(CHECKPOINT_TIMEOUT_MS) { checkpointReady.await() }
            assertEquals(1, engine.nativeContextDebugSnapshot().activeCheckpointCount)

            backgroundMainActivity()
            withTimeout(CHECKPOINT_TIMEOUT_MS) { generation.join() }

            assertTrue("Cycle $cycle generation job was not cancelled", generation.isCancelled)
            assertEquals(
                "Cycle $cycle leaked a native checkpoint",
                0,
                engine.nativeContextDebugSnapshot().activeCheckpointCount,
            )
            launchMainActivity(context)
            awaitResumedMainActivity()
        }

        installGenerationJob(awaitResumedMainActivity(), null)
        assertEquals(0, engine.nativeContextDebugSnapshot().activeCheckpointCount)
    }

    private suspend fun readyTextEngine(context: Context): LlamaEngine {
        val engine = LlamaEngine.getInstance(context)
        val initializedState = withTimeout(ENGINE_INIT_TIMEOUT_MS) {
            engine.state.first { state ->
                state is LlamaState.Initialized ||
                    state is LlamaState.ModelReady ||
                    state is LlamaState.Error
            }
        }
        check(initializedState !is LlamaState.Error) { "Native initialization failed" }
        if (initializedState is LlamaState.Initialized) {
            val model = File(LlamaEngine.modelPath(context))
            check(model.isFile) { "Production model is not installed: ${model.absolutePath}" }
            withTimeout(MODEL_LOAD_TIMEOUT_MS) {
                engine.loadModel(model.absolutePath, null)
            }
        }
        return engine
    }

    private suspend fun awaitResumedMainActivity(): MainActivity =
        withTimeout(ACTIVITY_TIMEOUT_MS) {
            while (true) {
                val resumed = AtomicReference<MainActivity?>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    resumed.set(
                        ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>()
                            .singleOrNull(),
                    )
                }
                resumed.get()?.let { return@withTimeout it }
                delay(ACTIVITY_POLL_MS)
            }
            error("Unreachable")
        }

    private fun installGenerationJob(activity: MainActivity, job: Job?) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            GENERATION_JOB_FIELD.set(activity, job)
        }
    }

    private fun bringDebugHostToForeground(context: Context) {
        executeShell(
            "am start -W -n ${context.packageName}/.CheckpointTestHostActivity",
            expectedMarker = "Status: ok",
        )
    }

    private fun launchMainActivity(context: Context) {
        executeShell(
            mainActivityLaunchCommand(context),
            expectedMarker = "Activity: ${context.packageName}/.MainActivity",
        )
    }

    private fun backgroundMainActivity() {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_HOME")
        readShellResult(descriptor)
    }

    private fun mainActivityLaunchCommand(context: Context): String =
        "am start -W -f 0x34000000 -n ${context.packageName}/.MainActivity"

    private fun executeShell(command: String, expectedMarker: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        val result = readShellResult(descriptor)
        check(result.contains(expectedMarker)) {
            "Shell command did not complete as expected: $result"
        }
    }

    private fun readShellResult(descriptor: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }

    private companion object {
        val GENERATION_JOB_FIELD = MainActivity::class.java
            .getDeclaredField("generationJob")
            .apply { isAccessible = true }
        const val BACKGROUND_CYCLES = 20
        const val ENGINE_INIT_TIMEOUT_MS = 30_000L
        const val MODEL_LOAD_TIMEOUT_MS = 180_000L
        const val CHECKPOINT_TIMEOUT_MS = 15_000L
        const val ACTIVITY_TIMEOUT_MS = 15_000L
        const val ACTIVITY_POLL_MS = 50L
    }
}
