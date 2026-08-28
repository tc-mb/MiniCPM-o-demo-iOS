package com.example.minicpm_v_demo

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout

/** Keeps debug instrumentation in a foreground process during native model tests. */
class CheckpointTestHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(FrameLayout(this))
    }
}
