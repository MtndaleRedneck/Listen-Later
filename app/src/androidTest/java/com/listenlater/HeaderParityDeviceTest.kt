package com.listenlater

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Home header and Settings header must be pixel-identical: same top, same bottom, same title
 * position, same button position, same first-content edge. Measured from the real rendered
 * layout (semantics bounds in px on the device) and two real screenshots are saved.
 */
@RunWith(AndroidJUnit4::class)
class HeaderParityDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private data class M(val header: Rect, val title: Rect, val button: Rect, val content: Rect)

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun cd(d: String) = rule.onNodeWithContentDescription(d).fetchSemanticsNode().boundsInRoot
    private fun shot(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "header-parity").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun homeAndSettingsHeadersAreIdentical() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val i = Intent(ctx, MainActivity::class.java).putExtra("demo", true)
        ActivityScenario.launch<MainActivity>(i).use {
            rule.waitUntil(20_000) { runCatching { bounds("content_top"); true }.getOrDefault(false) }
            rule.waitForIdle()
            val home = M(bounds("screen_header"), bounds("screen_title"), cd("Settings"), bounds("content_top"))
            shot("home")
            rule.onNodeWithContentDescription("Settings").performClick()
            rule.waitUntil(10_000) { runCatching { bounds("content_top"); bounds("screen_title").top; rule.onNodeWithContentDescription("Back").fetchSemanticsNode(); true }.getOrDefault(false) }
            rule.waitForIdle()
            val set = M(bounds("screen_header"), bounds("screen_title"), cd("Back"), bounds("content_top"))
            shot("settings")
            android.util.Log.i("HeaderParity", "home=$home settings=$set"); println("HEADER_PARITY home=$home settings=$set")
            assertEquals("header top", home.header.top, set.header.top, 0f)
            assertEquals("header bottom", home.header.bottom, set.header.bottom, 0f)
            assertEquals("title center y", home.title.center.y, set.title.center.y, 0f)
            assertEquals("title height", home.title.height, set.title.height, 0f)
            assertEquals("button top", home.button.top, set.button.top, 0f)
            assertEquals("button height", home.button.height, set.button.height, 0f)
            assertEquals("first content top", home.content.top, set.content.top, 0f)
        }
    }
}
