package com.listenlater

import android.content.Context
import android.view.View
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UiPrefsTest {
    private class CountingView(c: Context) : View(c) {
        var ticks = 0
        override fun performHapticFeedback(feedbackConstant: Int): Boolean { ticks++; return true }
    }

    private fun fresh(): Context {
        val c = ApplicationProvider.getApplicationContext<Context>()
        c.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        UiPrefs.resetForTest()
        return c
    }

    @Test fun defaultsAreSageAndVibrateOn() {
        UiPrefs.init(fresh())
        assertEquals(SAGE_ARGB.toInt(), UiPrefs.accent.toArgb())
        assertTrue(UiPrefs.haptics)
    }

    @Test fun choicesSurviveRestart() {
        val c = fresh()
        UiPrefs.init(c)
        UiPrefs.setAccent(Swatches[6]); UiPrefs.setHaptics(false)
        UiPrefs.resetForTest(); UiPrefs.init(c)
        assertEquals(Swatches[6].toArgb(), UiPrefs.accent.toArgb())
        assertFalse(UiPrefs.haptics)
    }

    @Test fun pickerHasEightColorsAndNoBingeMoteOrange() {
        assertEquals(8, Swatches.size)
        assertFalse(Swatches.any { it.toArgb() == 0xFFF97C02.toInt() })
        assertTrue(Swatches.any { it.toArgb() == 0xFFCC5500.toInt() })
    }

    @Test fun unknownSavedColorFallsBackToSage() {
        val c = fresh()
        c.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE).edit().putInt("accent", 0xFFF97C02.toInt()).commit()
        UiPrefs.init(c)
        assertEquals(SAGE_ARGB.toInt(), UiPrefs.accent.toArgb())
    }

    @Test fun tickOnlyWhenVibrateIsOn() {
        val c = fresh()
        UiPrefs.init(c)
        val v = CountingView(c)
        tapHaptic(v)
        assertEquals(1, v.ticks)
        UiPrefs.setHaptics(false)
        tapHaptic(v)
        assertEquals(1, v.ticks)
        tapHaptic(v, true) // an explicit "on" (turning the switch on) still ticks
        assertEquals(2, v.ticks)
    }
}
