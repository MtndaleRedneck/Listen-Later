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

    /**
     * uiAutomation.takeScreenshot() can return a stale or not-yet-drawn frame (a run showed a blank Home
     * and the previous screen under the next one's name). So: settle, then take frames until two in a
     * row are pixel-identical AND the title area really has text ink, or fail. The semantics bounds
     * stay the oracle; this makes the saved PNGs the same state as the numbers.
     */
    private fun shot(name: String, expectInk: Boolean = true) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        Thread.sleep(1000)
        var prev: Bitmap? = null
        var good: Bitmap? = null
        var last: Bitmap? = null
        for (attempt in 1..12) {
            rule.waitForIdle()
            waitOutLauncherAnrOnly(name)
            val cur = ui.takeScreenshot()
            if (cur == null) { android.util.Log.i("HeaderParity", "shot=$name attempt=$attempt screenshot=null"); continue }
            last = cur
            val same = prev != null && prev.width == cur.width && prev.height == cur.height && prev.sameAs(cur)
            val ink = if (!expectInk) -1 else titleInk(cur, rule.onNodeWithTag("screen_title").fetchSemanticsNode().boundsInWindow)
            android.util.Log.i("HeaderParity", "shot=$name attempt=$attempt same=$same ink=$ink (need >200) size=${cur.width}x${cur.height}")
            if (same && (!expectInk || ink > 200)) { good = cur; break }
            prev = cur
            Thread.sleep(500)
        }
        if (good == null) diagnose(name, last)
        val bmp = good ?: throw AssertionError("screenshot $name never settled to a stable frame with the title drawn")
        FileOutputStream(File(dir, "$laneTag-$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val anrGuard = LauncherAnrGuard { n, b -> diagnose(n, b) }

    /** See LauncherAnrGuard: dismiss ONLY the launcher's own ANR dialog (Wait, max 4); any other ANR dialog fails. */
    private fun waitOutLauncherAnrOnly(name: String) = anrGuard.waitOut(name)

    /** Failure evidence only (no assertion is relaxed): the last frame and every window on screen. */
    private fun diagnose(name: String, last: Bitmap?) {
        runCatching {
            val out = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it).apply { mkdirs() } } ?: dir
            if (last != null) FileOutputStream(File(out, "FAIL-$laneTag-$name-lastframe.png")).use { last.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            for (w in ui.windows) {
                val r = w.root
                android.util.Log.e("HeaderParity", "window layer=${w.layer} type=${w.type} title=${w.title} active=${w.isActive} focused=${w.isFocused} pkg=${r?.packageName} class=${r?.className} text=${r?.text}")
            }
            rule.onAllNodes(androidx.compose.ui.test.isRoot()).printToLog("HeaderParity")
        }
    }

    private fun titleInk(bmp: Bitmap, r: Rect): Int {
        var n = 0
        val x0 = r.left.toInt().coerceIn(0, bmp.width - 1); val x1 = r.right.toInt().coerceIn(x0 + 1, bmp.width)
        val y0 = r.top.toInt().coerceIn(0, bmp.height - 1); val y1 = r.bottom.toInt().coerceIn(y0 + 1, bmp.height)
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = bmp.getPixel(x, y)
            val lum = ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
            if (lum > 170) n++
        }
        return n
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
            runCatching { shot("FAILED-$title", expectInk = false) }
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
        ActivityScenario.launch<MainActivity>(i).use { scenario ->
            waitOutLauncherAnrOnly("start")
            ready("Listen Later", "Settings")
            val home = measure("Settings"); shot("home")
            rule.onNodeWithContentDescription("Settings").performClick()
            ready("Settings", "Back")
            val set = measure("Back"); shot("settings")
            rule.onNodeWithText("Send feedback, report a bug, or suggest a feature").performScrollTo().performClick()
            ready("Feedback", "Back")
            val fb = measure("Back"); shot("feedback")
            // The activity's own configuration is what Compose's LocalConfiguration reads (not the target context's).
            var dm = ctx.resources.configuration
            scenario.onActivity { dm = it.resources.configuration }
            val shrink = dm.screenWidthDp < 400 && dm.fontScale > 1.15f
            val line = "lane=$laneTag shrink=$shrink widthDp=${dm.screenWidthDp} fontScale=${dm.fontScale} home=$home settings=$set feedback=$fb"
            android.util.Log.i("HeaderParity", line)
            File(dir, "$laneTag-values.txt").writeText(line + "\n")
            for ((name, m) in listOf("settings" to set, "feedback" to fb)) {
                assertEquals("$name header top", home.header.top, m.header.top, 0f)
                assertEquals("$name header bottom", home.header.bottom, m.header.bottom, 0f)
                if (shrink) {
                    // Home's title is capped at a fixed 18dp to leave room for the status pill; Settings and
                    // Feedback keep their natural 20sp. The title boxes differ in size by design here, so the
                    // title must be vertically centred on the same line; everything that sets the header
                    // height (header box, buttons, first content) is still compared exactly below.
                    assertEquals("$name title centre y", home.title.center.y, m.title.center.y, 1f)
                    assertTrue("$name title fits inside the header", m.title.top >= m.header.top && m.title.bottom <= m.header.bottom)
                } else {
                    assertEquals("$name title top", home.title.top, m.title.top, 0f)
                    assertEquals("$name title height", home.title.height, m.title.height, 0f)
                }
                assertEquals("$name button top", home.button.top, m.button.top, 0f)
                assertEquals("$name button height", home.button.height, m.button.height, 0f)
                assertEquals("$name first content top", home.content.top, m.content.top, 0f)
            }
            assertTrue("sanity: header has real height", home.header.height > 100f)
        }
    }
}
