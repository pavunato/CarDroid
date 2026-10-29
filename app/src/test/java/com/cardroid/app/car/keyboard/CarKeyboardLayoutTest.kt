package com.cardroid.app.car.keyboard

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarKeyboardLayoutTest {
    @Test fun shiftGivesOneCapitalThenTurnsOff() {
        val once = ShiftState().tap(1_000)
        assertEquals("Q", once.apply("q"))
        assertEquals(ShiftState.Mode.OFF, once.afterChar().mode)
    }
    @Test fun doubleTapLocksCapsUntilTappedAgain() {
        val locked = ShiftState().tap(1_000).tap(1_200)
        assertEquals(ShiftState.Mode.LOCKED, locked.mode)
        assertEquals(ShiftState.Mode.LOCKED, locked.afterChar().mode)
        assertEquals(ShiftState.Mode.OFF, locked.tap(5_000).mode)
    }
    @Test fun slowSecondTapTurnsShiftOff() {
        assertEquals(ShiftState.Mode.OFF, ShiftState().tap(1_000).tap(1_000 + ShiftState.DOUBLE_TAP_MS + 1).mode)
    }
    @Test fun everyPageHasBackspaceSpaceEnterAndHide() {
        Page.entries.forEach { page ->
            val keys = CarKeyboardLayout.rows(page).flatten()
            listOf(Key.Backspace, Key.Space, Key.Enter, Key.Hide).forEach { assertTrue("$page lacks $it", it in keys) }
        }
    }
    @Test fun letterPageIsQwerty() {
        val first = CarKeyboardLayout.rows(Page.LETTERS).first().joinToString("") { (it as Key.Char).value }
        assertEquals("qwertyuiop", first)
    }
    @Test fun enterFollowsTheEditorAction() {
        assertEquals(EnterAction.SEARCH, EnterAction.of(EditorInfo.IME_ACTION_SEARCH, EditorInfo.TYPE_CLASS_TEXT))
        assertEquals(EnterAction.GO, EnterAction.of(EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI, 0x80011))
    }
    @Test fun enterWithoutActionIsNewlineForMultiLineElseEnter() {
        val multi = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(EnterAction.NEWLINE, EnterAction.of(EditorInfo.IME_ACTION_UNSPECIFIED, multi))
        assertEquals(EnterAction.NEWLINE, EnterAction.of(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION, multi))
        assertEquals(EnterAction.ENTER, EnterAction.of(EditorInfo.IME_ACTION_NONE, EditorInfo.TYPE_CLASS_TEXT))
    }
    @Test fun submittingActionsHideTheKeyboard() {
        assertEquals(setOf(EnterAction.GO, EnterAction.SEARCH, EnterAction.SEND, EnterAction.DONE), EnterAction.entries.filter { it.hidesKeyboard }.toSet())
    }
    @Test fun panelTapHidesUnlessTheEditorReportsAClickAfterIt() {
        assertTrue(KeyboardRules.hideAfterPanelTap(tapAtMs = 1_000, lastEditorClickMs = 0))
        assertTrue(KeyboardRules.hideAfterPanelTap(tapAtMs = 1_000, lastEditorClickMs = 900))
        assertEquals(false, KeyboardRules.hideAfterPanelTap(tapAtMs = 1_000, lastEditorClickMs = 1_050))
    }
}
