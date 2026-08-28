package com.example.minicpm_v_demo

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding

/** Keeps the system status bar visible when an activity starts or regains focus. */
abstract class StatusBarVisibleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onContentChanged() {
        super.onContentChanged()
        val content = findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    override fun onResume() {
        super.onResume()
        showStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showStatusBar()
    }

    private fun showStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.statusBars())
    }
}
