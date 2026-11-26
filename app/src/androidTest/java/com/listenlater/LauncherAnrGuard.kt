package com.listenlater

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The emulator's launcher can show "Pixel Launcher isn't responding" over the app; it dims the screen, so screenshot
 * checks can never pass. Dismiss ONLY that exact dialog, ONLY with its own Wait button, at most [maxClicks] times;
 * any other "isn't responding" dialog (including one for this app) fails immediately with the dialog title.
 * No blanket dialog mask, no assertion relaxed.
 *
 * Window visibility: UiAutomation.getWindows() returns windows only when the service info has
 * AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS (documented on UiAutomation.getWindows /
 * setServiceInfo). Run 45770391929 saw the dialog in the saved frame but the handler found nothing, so the flag is
 * now set explicitly through UiAutomation.setServiceInfo, and the active-window root is scanned too.
 * Every scan logs the window count and active-root detection (tag LauncherAnr).
 */
class LauncherAnrGuard(private val maxClicks: Int = 4, private val diagnose: (String, Bitmap?) -> Unit) {
    var clicks = 0
        private set

    fun waitOut(name: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            scan(name, ui)
        } catch (t: Throwable) {
            // Keep the failure evidence (frame, windows, tree) before the error leaves; then rethrow unchanged.
            diagnose("$name-anr-fail", ui.takeScreenshot())
            throw t
        }
    }

    private fun enableInteractiveWindows(ui: UiAutomation) {
        val info = ui.serviceInfo ?: return
        if ((info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.serviceInfo = info
        }
    }

    private fun scan(name: String, ui: UiAutomation) {
        enableInteractiveWindows(ui)
        val windows = ui.windows
        val roots = ArrayList<AccessibilityNodeInfo>()
        for (w in windows) w.root?.let { roots.add(it) }
        val active = ui.rootInActiveWindow
        if (active != null && roots.none { it == active }) roots.add(active)
        android.util.Log.i("LauncherAnr", "scan=$name windows=${windows.size} roots=${roots.size} activeRoot=${active?.packageName}")
        for (root in roots) {
            val hits = root.findAccessibilityNodeInfosByText("responding").filter { norm(it.text?.toString()).contains("isn't responding") }
            if (hits.isEmpty()) continue
            val title = norm(hits.first().text?.toString())
            if (title != "Pixel Launcher isn't responding") throw AssertionError("unexpected ANR dialog (not the launcher): '$title'")
            if (clicks >= maxClicks) throw AssertionError("launcher ANR dialog still present after $maxClicks Wait clicks")
            val wait = root.findAccessibilityNodeInfosByText("Wait").firstOrNull() ?: throw AssertionError("launcher ANR dialog has no Wait button")
            diagnose("$name-anr-predismiss-$clicks", ui.takeScreenshot()) // frame + windows + logcat BEFORE the click
            var n: AccessibilityNodeInfo? = wait
            while (n != null && !n.isClickable) n = n.parent
            if (n == null || !n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw AssertionError("could not click Wait on the launcher ANR dialog")
            clicks++
            android.util.Log.i("LauncherAnr", "launcher ANR dialog dismissed with Wait (click $clicks of max $maxClicks)")
            Thread.sleep(1000)
        }
    }

    // Only the apostrophe glyph is normalised (typographic -> ASCII); the title must still match exactly.
    private fun norm(s: String?): String = (s ?: "").replace('\u2019', '\'')
}
