package com.listenlater

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Home, Settings and Feedback headers must be pixel-identical: same header top/bottom, title
 * position and size, button position, and first-content edge. Measured from the real rendered
 * layout (semantics bounds in px on the device), zero tolerance, with a real screenshot per
 * screen. Runs under whatever font scale / size the lane set; the lane tag is in the file names.
 */
@RunWith(AndroidJUnit4::class)
class HeaderParityDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private data class M(val header: Rect, val title: Rect, val button: Rect, val content: Rect)

    private fun b(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun cd(d: String) = rule.onNodeWithContentDescription(d).fetchSemanticsNode().boundsInRoot
    private val laneTag get() = InstrumentationRegistry.getArguments().getString("lane") ?: "default"
    private val dir get() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "header-parity").apply { mkdirs() }

    private fun shot(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        FileOutputStream(File(dir, "$laneTag-$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun titleText() = rule.onNodeWithTag("screen_title").fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    /** Wait until THIS screen (by its own title text) is on screen and laid out, never a neighbour's header. */
    private fun ready(title: String, button: String) {
        try {
            rule.waitUntil(20_000) {
                runCatching { b("content_top"); b("screen_header"); cd(button); titleText() == title }.getOrDefault(false)
            }
        } catch (t: Throwable) {
            // Fail closed, but leave the evidence: what the semantics tree and the screen really looked like.
            runCatching { shot("FAILED-$title") }
            runCatching { rule.onAllNodes(androidx.compose.ui.test.isRoot()).printToLog("HeaderParity") }
            android.util.Log.e("HeaderParity", "not ready: $title (button $button)", t)
            throw t
        }
        rule.waitForIdle()
        assertEquals("on the $title screen", title, titleText())
    }

    private fun measure(button: String) = M(b("screen_header"), b("screen_title"), cd(button), b("content_top"))

    @Test fun homeSettingsAndFeedbackHeadersAreIdentical() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // A notification-permission dialog over the app pauses it and starves composition; grant first.
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        val i = Intent(ctx, MainActivity::class.java).putExtra("demo", true)
        ActivityScenario.launch<MainActivity>(i).use {
            ready("Listen Later", "Settings")
            val home = measure("Settings"); shot("home")
            rule.onNodeWithContentDescription("Settings").performClick()
            ready("Settings", "Back")
            val set = measure("Back"); shot("settings")
            rule.onNodeWithText("Send feedback, report a bug, or suggest a feature").performScrollTo().performClick()
            ready("Feedback", "Back")
            val fb = measure("Back"); shot("feedback")
            val line = "lane=$laneTag home=$home settings=$set feedback=$fb"
            android.util.Log.i("HeaderParity", line)
            File(dir, "$laneTag-values.txt").writeText(line + "\n")
            for ((name, m) in listOf("settings" to set, "feedback" to fb)) {
                assertEquals("$name header top", home.header.top, m.header.top, 0f)
                assertEquals("$name header bottom", home.header.bottom, m.header.bottom, 0f)
                assertEquals("$name title top", home.title.top, m.title.top, 0f)
                assertEquals("$name title height", home.title.height, m.title.height, 0f)
                assertEquals("$name button top", home.button.top, m.button.top, 0f)
                assertEquals("$name button height", home.button.height, m.button.height, 0f)
                assertEquals("$name first content top", home.content.top, m.content.top, 0f)
            }
            assertTrue("sanity: header has real height", home.header.height > 100f)
        }
    }
}
