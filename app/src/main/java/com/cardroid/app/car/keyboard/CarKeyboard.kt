package com.cardroid.app.car.keyboard

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
    /** The focused editor; a new [generation] per focus so the launcher can re-show a keyboard the user hid. */
    data class Editor(val action: EnterAction, val generation: Long)

    private const val OWN_EDIT_ECHO_MS = 500L
    private val current = MutableStateFlow<Editor?>(null)
    val editor: StateFlow<Editor?> = current.asStateFlow()
    @Volatile private var connection: InputConnection? = null
    @Volatile private var owner: Any? = null
    private var generation = 0L

    internal fun attach(service: Any, ic: InputConnection?, info: EditorInfo) {
        if (ic == null || info.inputType == EditorInfo.TYPE_NULL) { detach(service); return }
        owner = service; connection = ic
        val action = EnterAction.of(info.imeOptions, info.inputType)
        current.value = Editor(action, ++generation)
        CarDroidLog.log("CarKeyboard: editor ${info.packageName} action=${action.label}")
    }

    internal fun detach(service: Any) {
        if (owner !== service) return
        connection = null; current.value = null
    }

    @Volatile private var lastOwnEditMs = 0L

    /**
     * The user tapped the focused editor (or moved its cursor): show the keyboard again if they had hidden it. Selection
     * changes right after our own keys are ignored. A focused field's tap never restarts input, so this is the only signal.
     */
    internal fun editorTouched(service: Any, selectionChange: Boolean) {
        if (owner !== service) return
        if (selectionChange && System.currentTimeMillis() - lastOwnEditMs < OWN_EDIT_ECHO_MS) return
        current.value?.let { current.value = it.copy(generation = ++generation) }
    }

    fun type(text: String) { lastOwnEditMs = System.currentTimeMillis(); connection?.commitText(text, 1) }

    fun backspace() {
        lastOwnEditMs = System.currentTimeMillis()
        val ic = connection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingTextInCodePoints(1, 0)
    }

    fun enter() {
        lastOwnEditMs = System.currentTimeMillis()
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
