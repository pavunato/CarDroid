package com.cardroid.app.car.keyboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.cardroid.app.CarDroidLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge between [CarInputMethodService] (bound by Android to the focused car-panel editor, but unable to show a window
 * there) and the keyboard the car launcher draws on the car screen. Keys go straight into the editor's InputConnection.
 */
object CarKeyboard {
    /** The focused editor and whether the car keyboard is up for it. */
    data class Editor(val action: EnterAction, val visible: Boolean = true)

    /** How long after a panel tap the editor has to report a click before the keyboard hides. */
    private const val EDITOR_CLICK_WINDOW_MS = 400L
    private val current = MutableStateFlow<Editor?>(null)
    val editor: StateFlow<Editor?> = current.asStateFlow()
    @Volatile private var connection: InputConnection? = null
    @Volatile private var owner: Any? = null
    @Volatile private var lastEditorClickMs = 0L
    private val main = Handler(Looper.getMainLooper())

    internal fun attach(service: Any, ic: InputConnection?, info: EditorInfo) {
        if (ic == null || info.inputType == EditorInfo.TYPE_NULL) { detach(service); return }
        owner = service; connection = ic
        val action = EnterAction.of(info.imeOptions, info.inputType)
        current.value = Editor(action)
        CarDroidLog.log("CarKeyboard: editor ${info.packageName} action=${action.label}")
    }

    internal fun detach(service: Any) {
        if (owner !== service) return
        connection = null; current.value = null
    }

    /** The user tapped the focused editor: bring a hidden keyboard back (a focused field's tap never restarts input). */
    internal fun editorClicked(service: Any) {
        if (owner !== service) return
        lastEditorClickMs = SystemClock.uptimeMillis()
        current.value?.let { if (!it.visible) current.value = it.copy(visible = true) }
    }

    fun hide() { current.value?.let { if (it.visible) current.value = it.copy(visible = false) } }

    /**
     * A tap landed in a car panel. Apps hide the keyboard themselves when the user picks a suggestion or leaves the field,
     * but that request cannot reach us on car displays, so hide unless the tap turns out to be on the editor. Only while
     * the keyboard is up.
     */
    fun panelTapped() {
        if (current.value?.visible != true) return
        val tapAt = SystemClock.uptimeMillis()
        main.postDelayed({ if (KeyboardRules.hideAfterPanelTap(tapAt, lastEditorClickMs)) hide() }, EDITOR_CLICK_WINDOW_MS)
    }

    fun type(text: String) { connection?.commitText(text, 1) }

    fun backspace() {
        val ic = connection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingTextInCodePoints(1, 0)
    }

    fun enter() {
        val ic = connection ?: return
        val action = current.value?.action ?: EnterAction.ENTER
        when {
            action.editorAction != null -> ic.performEditorAction(action.editorAction)
            action == EnterAction.NEWLINE -> ic.commitText("\n", 1)
            else -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }
}

internal object KeyboardRules {
    /**
     * Hide after a panel tap unless the editor reported a click at or after it. Input restarts don't count: Maps restarts
     * input when a picked suggestion opens its results. Only taps while the keyboard is up get here, so a tap on a fake
     * search box that opens a fresh field still shows the keyboard.
     */
    fun hideAfterPanelTap(tapAtMs: Long, lastEditorClickMs: Long): Boolean = lastEditorClickMs < tapAtMs
}

/** What the car keyboard's Enter key does for the focused editor (its imeOptions action, else newline or Enter). */
enum class EnterAction(val label: String, val editorAction: Int?) {
    GO("Go", EditorInfo.IME_ACTION_GO), SEARCH("Search", EditorInfo.IME_ACTION_SEARCH), SEND("Send", EditorInfo.IME_ACTION_SEND),
    NEXT("Next", EditorInfo.IME_ACTION_NEXT), DONE("Done", EditorInfo.IME_ACTION_DONE), PREVIOUS("Back", EditorInfo.IME_ACTION_PREVIOUS),
    NEWLINE("Enter", null), ENTER("Enter", null);

    /** Submitting actions end typing, so the keyboard hides like a phone keyboard does. */
    val hidesKeyboard: Boolean get() = this == GO || this == SEARCH || this == SEND || this == DONE

    companion object {
        fun of(imeOptions: Int, inputType: Int): EnterAction {
            val multiLine = inputType and EditorInfo.TYPE_MASK_CLASS == EditorInfo.TYPE_CLASS_TEXT &&
                inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE != 0
            if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return if (multiLine) NEWLINE else ENTER
            return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_GO -> GO
                EditorInfo.IME_ACTION_SEARCH -> SEARCH
                EditorInfo.IME_ACTION_SEND -> SEND
                EditorInfo.IME_ACTION_NEXT -> NEXT
                EditorInfo.IME_ACTION_DONE -> DONE
                EditorInfo.IME_ACTION_PREVIOUS -> PREVIOUS
                else -> if (multiLine) NEWLINE else ENTER
            }
        }
    }
}
