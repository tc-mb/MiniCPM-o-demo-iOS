// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

gradle.taskGraph.whenReady {
    val blockedTasks = allTasks.filter { task ->
        task.name == "connectedCheck" ||
            (task.name.startsWith("connected") && task.name.endsWith("AndroidTest"))
    }
    if (blockedTasks.isNotEmpty()) {
        throw GradleException(
            "CONNECTED_DEVICE_TEST_BLOCKED: connected Android instrumentation can uninstall " +
                "the target app and erase private user data. Build the test APK with " +
                ":app:assembleDebugAndroidTest, install both APKs with adb install -r, then run " +
                "scripts/run-device-instrumentation.ps1. Blocked: " +
                blockedTasks.joinToString { it.path },
        )
    }
}
