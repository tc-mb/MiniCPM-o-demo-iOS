package com.example.minicpm_v_demo

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.recyclerview.widget.LinearLayoutManager
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @Test
    fun modelManagerToolbarStartsBelowStatusBar() {
        val toolbarBelowStatusBar = AtomicBoolean(false)
        val verified = CountDownLatch(1)

        ActivityScenario.launch(ModelManagerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<View>(R.id.toolbar)
                toolbar.post {
                    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    val statusBarHeight = insets
                        ?.getInsets(WindowInsetsCompat.Type.statusBars())
                        ?.top ?: 0
                    val location = IntArray(2)
                    toolbar.getLocationOnScreen(location)
                    toolbarBelowStatusBar.set(
                        statusBarHeight > 0 && location[1] >= statusBarHeight
                    )
                    verified.countDown()
                }
            }

            assertTrue(verified.await(5, TimeUnit.SECONDS))
            assertTrue(
                "The model manager toolbar must start below the status bar",
                toolbarBelowStatusBar.get()
            )
        }
    }

    @Test
    fun chatScreenStartsBelowVisibleStatusBarAndHasPendingImagePanel() {
        val statusBarVisible = AtomicBoolean(false)
        val verified = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val pendingPanel = activity.findViewById<View>(R.id.pending_image_panel)
                val settingsButton = activity.findViewById<View>(R.id.btn_settings)
                val title = activity.findViewById<View>(R.id.tv_title)
                val cameraButton = activity.findViewById<View>(R.id.btn_camera)
                val sendButton = activity.findViewById<View>(R.id.btn_send)
                val preprocessingStatus = activity.findViewById<View>(R.id.tv_pending_image_status)
                val removePendingImage = activity.findViewById<View>(R.id.btn_remove_pending_image)

                assertNotNull(pendingPanel)
                assertNotNull(settingsButton)
                assertNotNull(title)
                assertNotNull(cameraButton)
                assertNotNull(sendButton)
                assertNotNull(preprocessingStatus)
                assertNotNull(removePendingImage)
                assertTrue(pendingPanel.visibility == View.GONE)

                val parent = cameraButton.parent as ViewGroup
                assertTrue(parent.indexOfChild(cameraButton) < parent.indexOfChild(sendButton))

                val settingsLocation = IntArray(2)
                val titleLocation = IntArray(2)
                settingsButton.getLocationOnScreen(settingsLocation)
                title.getLocationOnScreen(titleLocation)
                assertTrue(
                    "The unified settings entry must be left of the title",
                    settingsLocation[0] < titleLocation[0]
                )

                activity.window.decorView.post {
                    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    statusBarVisible.set(
                        insets != null &&
                            insets.isVisible(WindowInsetsCompat.Type.statusBars())
                    )
                    verified.countDown()
                }
            }

            assertTrue(verified.await(5, TimeUnit.SECONDS))
            assertTrue("The Android status bar must remain visible", statusBarVisible.get())
        }
    }

    @Test
    fun keyboardPreservesBottomAnchorAndDismissesOnlyOnTap(): Unit = runBlocking {
        bringDebugHostToForeground()
        launchMainActivity()
        val activity = awaitResumedMainActivity()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMessages = AtomicReference<List<ChatMessage>>()
        val anchorDistanceBeforeIme = AtomicReference<Int>()

        try {
            instrumentation.runOnMainSync {
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                    R.id.recycler_chat,
                )
                val input = activity.findViewById<android.widget.EditText>(R.id.et_input)
                val adapter = recycler.adapter as ChatAdapter
                originalMessages.set(adapter.currentList.toList())
                val longReply = (1..80).joinToString("\n") { line ->
                    "Keyboard viewport regression line $line"
                }
                adapter.submitList(
                    listOf(ChatMessage.AiMessage(id = Long.MAX_VALUE, text = longReply)),
                ) {
                    recycler.post {
                        recycler.scrollBy(0, 1_200)
                        recycler.post {
                            val layoutManager = recycler.layoutManager as LinearLayoutManager
                            val lastView = layoutManager.findViewByPosition(adapter.itemCount - 1)
                            checkNotNull(lastView)
                            val contentBottom = recycler.height - recycler.paddingBottom
                            anchorDistanceBeforeIme.set(contentBottom - lastView.top)
                            input.isEnabled = true
                            input.requestFocus()
                            activity.getSystemService(InputMethodManager::class.java)
                                .showSoftInput(input, 0)
                            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                                .show(WindowInsetsCompat.Type.ime())
                        }
                    }
                }
            }

            val bottomAnchorPreserved = withTimeoutOrNull(5_000) {
                while (true) {
                    val anchorIsPreserved = AtomicBoolean(false)
                    instrumentation.runOnMainSync {
                        val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                            R.id.recycler_chat,
                        )
                        val adapter = recycler.adapter as ChatAdapter
                        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        val layoutManager = recycler.layoutManager as LinearLayoutManager
                        val lastView = layoutManager.findViewByPosition(adapter.itemCount - 1)
                        if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                            lastView != null && recycler.height > 0 &&
                            anchorDistanceBeforeIme.get() != null
                        ) {
                            val contentBottom = recycler.height - recycler.paddingBottom
                            val currentDistance = contentBottom - lastView.top
                            anchorIsPreserved.set(
                                abs(currentDistance - anchorDistanceBeforeIme.get()) <= 2,
                            )
                        }
                    }
                    if (anchorIsPreserved.get()) return@withTimeoutOrNull true
                    delay(50)
                }
                false
            } ?: false
            assertTrue(
                "Opening the keyboard must preserve the conversation content at the bottom edge",
                bottomAnchorPreserved,
            )

            val gesture = AtomicReference<IntArray>()
            val offsetBeforeSwipe = AtomicReference<Int>()
            instrumentation.runOnMainSync {
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                    R.id.recycler_chat,
                )
                val location = IntArray(2).also(recycler::getLocationOnScreen)
                val x = location[0] + recycler.width / 2
                val startY = location[1] + recycler.height / 3
                val endY = (startY + recycler.height / 3)
                    .coerceAtMost(location[1] + recycler.height - 20)
                gesture.set(intArrayOf(x, startY, endY))
                offsetBeforeSwipe.set(recycler.computeVerticalScrollOffset())
            }
            val (x, startY, endY) = gesture.get()
            executeShell("input swipe $x $startY $x $endY 300")

            val swipeKeptKeyboardAndScrolled = withTimeout(5_000) {
                while (true) {
                    val result = AtomicReference<Boolean?>()
                    instrumentation.runOnMainSync {
                        val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                            R.id.recycler_chat,
                        )
                        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        val offsetChanged = recycler.computeVerticalScrollOffset() < offsetBeforeSwipe.get()
                        if (offsetChanged) {
                            result.set(insets?.isVisible(WindowInsetsCompat.Type.ime()) == true)
                        }
                    }
                    result.get()?.let { return@withTimeout it }
                    delay(50)
                }
                error("Unreachable")
            }
            assertTrue(
                "Swiping the conversation must scroll without dismissing the keyboard",
                swipeKeptKeyboardAndScrolled,
            )

            executeShell("input tap $x $startY")
            val keyboardDismissedByTap = withTimeoutOrNull(5_000) {
                while (true) {
                    val hidden = AtomicBoolean(false)
                    instrumentation.runOnMainSync {
                        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        hidden.set(insets?.isVisible(WindowInsetsCompat.Type.ime()) != true)
                    }
                    if (hidden.get()) return@withTimeoutOrNull true
                    delay(50)
                }
                false
            } ?: false
            assertTrue("Tapping the conversation must dismiss the keyboard", keyboardDismissedByTap)
        } finally {
            instrumentation.runOnMainSync {
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                    R.id.recycler_chat,
                )
                val input = activity.findViewById<android.widget.EditText>(R.id.et_input)
                (recycler.adapter as ChatAdapter).submitList(originalMessages.get().orEmpty())
                input.clearFocus()
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    @Test
    fun latestMessageUsesConversationSpacingAboveInputBar(): Unit = runBlocking {
        bringDebugHostToForeground()
        launchMainActivity()
        val activity = awaitResumedMainActivity()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val actualPadding = withTimeout(5_000) {
            while (true) {
                val result = AtomicReference<Int?>()
                instrumentation.runOnMainSync {
                    val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                        R.id.recycler_chat,
                    )
                    val inputBar = activity.findViewById<View>(R.id.card_input_bar)
                    if (inputBar.height > 0) result.set(recycler.paddingBottom)
                }
                result.get()?.let { return@withTimeout it }
                delay(50)
            }
            error("Unreachable")
        }
        val expectedPadding = (12 * activity.resources.displayMetrics.density).roundToInt()
        assertEquals(
            "The latest-message gap must match the 12dp spacing between chat bubbles",
            expectedPadding,
            actualPadding,
        )
    }

    private suspend fun awaitResumedMainActivity(): MainActivity = withTimeout(10_000) {
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
            delay(50)
        }
        error("Unreachable")
    }

    private fun bringDebugHostToForeground() {
        executeShell("am start -W -n com.example.minicpm_v_demo/.CheckpointTestHostActivity")
    }

    private fun launchMainActivity() {
        executeShell("am start -W -f 0x34000000 -n com.example.minicpm_v_demo/.MainActivity")
    }

    private fun executeShell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
