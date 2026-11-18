package com.messenger.app.push

import android.app.Activity
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/** UI assertions require an awake, unlocked display; no persistent setting is changed. */
internal fun wakeRoutingTestDisplay() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
        automation.executeShellCommand(command).use { output -> FileInputStream(output.fileDescriptor).use { it.readBytes() } }
    }
}
internal fun keepRoutingTestDisplay(activity: Activity) {
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
