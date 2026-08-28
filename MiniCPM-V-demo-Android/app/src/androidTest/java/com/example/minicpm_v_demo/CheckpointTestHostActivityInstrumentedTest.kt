package com.example.minicpm_v_demo

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CheckpointTestHostActivityInstrumentedTest {
    @Test
    fun hostActivityStaysResumedAndKeepsScreenAwake() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, CheckpointTestHostActivity::class.java),
            0,
        )
        assertTrue(activityInfo.exported)
        assertEquals(Manifest.permission.DUMP, activityInfo.permission)

        ActivityScenario.launch(CheckpointTestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertTrue(
                    activity.window.attributes.flags and
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
                )
            }
        }
    }
}
