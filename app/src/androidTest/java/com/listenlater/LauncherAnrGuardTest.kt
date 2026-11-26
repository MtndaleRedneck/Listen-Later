package com.listenlater

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Controlled proof for LauncherAnrGuard against a REAL window on a real emulator: the test shows its own dialog
 * (title text + a "Wait" button that counts clicks and does not dismiss) and runs the guard's branches:
 * dismiss-with-Wait, 4-click limit, other-app ANR title fails, no dialog = no click. It proves window
 * enumeration plus the handler branches, not that the emulator's launcher produces the same view tree.
 */
@RunWith(AndroidJUnit4::class)
class LauncherAnrGuardTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val guardDiag: (String, Bitmap?) -> Unit = { n, _ -> android.util.Log.i("LauncherAnr", "diagnose($n)") }

    private fun withDialog(title: String, body: (waitClicks: AtomicInteger) -> Unit) {
        val ctx = inst.targetContext
        runCatching { inst.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS") }
        val clicks = AtomicInteger()
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra("demo", true)).use { sc ->
            var dlg: Dialog? = null
            sc.onActivity { a ->
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(60, 60, 60, 60) }
                col.addView(TextView(a).apply { text = title; textSize = 20f })
                col.addView(Button(a).apply { text = "Wait"; setOnClickListener { clicks.incrementAndGet() } })
                dlg = Dialog(a).apply { setContentView(col); show() }
            }
            inst.waitForIdleSync()
            try { body(clicks) } finally { sc.onActivity { dlg?.dismiss() } }
        }
    }

    @Test fun launcherDialogIsFoundAndWaitIsClickedExactlyOncePerScan_thenFailsAtTheLimit() {
        withDialog("Pixel Launcher isn't responding") { clicks ->
            val g = LauncherAnrGuard(maxClicks = 2, diagnose = guardDiag)
            g.waitOut("t1"); assertEquals(1, g.clicks); assertEquals(1, clicks.get())
            g.waitOut("t2"); assertEquals(2, g.clicks); assertEquals(2, clicks.get())
            try { g.waitOut("t3"); fail("expected the click limit to fail") }
            catch (e: AssertionError) { assertTrue(e.message, e.message!!.contains("still present after 2 Wait clicks")) }
            assertEquals("no click beyond the limit", 2, clicks.get())
        }
    }

    @Test fun typographicApostropheLauncherTitleIsAlsoHandled() {
        withDialog("Pixel Launcher isn\u2019t responding") { clicks ->
            val g = LauncherAnrGuard(diagnose = guardDiag)
            g.waitOut("t"); assertEquals(1, clicks.get())
        }
    }

    @Test fun anotherAppsAnrDialogFailsImmediatelyWithItsTitle_andNoWaitIsClicked() {
        withDialog("Listen Later isn't responding") { clicks ->
            val g = LauncherAnrGuard(diagnose = guardDiag)
            try { g.waitOut("t"); fail("expected an unexpected-dialog failure") }
            catch (e: AssertionError) { assertTrue(e.message, e.message!!.contains("unexpected ANR dialog (not the launcher): 'Listen Later isn't responding'")) }
            assertEquals(0, clicks.get()); assertEquals(0, g.clicks)
        }
    }

    @Test fun noDialogMeansNoClickAndNoFailure() {
        val ctx = inst.targetContext
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra("demo", true)).use {
            val g = LauncherAnrGuard(diagnose = guardDiag)
            g.waitOut("none"); assertEquals(0, g.clicks)
        }
    }
}
