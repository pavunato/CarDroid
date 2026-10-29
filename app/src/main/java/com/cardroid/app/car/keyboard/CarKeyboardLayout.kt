package com.cardroid.app.car.keyboard

/** Car keyboard keys and pages; pure so the layout and shift behaviour are unit-tested. */
sealed interface Key {
    data class Char(val value: String) : Key
    data object Shift : Key
    data object Backspace : Key
    data object Symbols : Key
    data object Letters : Key
    data object Space : Key
    data object Enter : Key
    data object Hide : Key
}

enum class Page { LETTERS, SYMBOLS }

object CarKeyboardLayout {
    private fun chars(row: String) = row.map { Key.Char(it.toString()) }

    fun rows(page: Page): List<List<Key>> = when (page) {
        Page.LETTERS -> listOf(
            chars("qwertyuiop"),
            chars("asdfghjkl"),
            listOf(Key.Shift) + chars("zxcvbnm") + Key.Backspace,
            listOf(Key.Symbols, Key.Char(","), Key.Space, Key.Char("."), Key.Enter, Key.Hide),
        )
        Page.SYMBOLS -> listOf(
            chars("1234567890"),
            chars("@#$%&-+()/"),
            chars("*\"':;!?_=") + Key.Backspace,
            listOf(Key.Letters, Key.Char(","), Key.Space, Key.Char("."), Key.Enter, Key.Hide),
        )
    }

    /** Relative width of a key in its row (letters are 1). */
    fun weight(key: Key): Float = when (key) {
        Key.Space -> 4f
        Key.Shift, Key.Backspace, Key.Enter, Key.Symbols, Key.Letters -> 1.5f
        else -> 1f
    }
}

/** Off → one capital → caps lock (second tap within [DOUBLE_TAP_MS]) → off. */
data class ShiftState(val mode: Mode = Mode.OFF, val lastTapMs: Long = Long.MIN_VALUE / 2) {
    enum class Mode { OFF, ONCE, LOCKED }

    fun tap(nowMs: Long): ShiftState = when (mode) {
        Mode.OFF -> ShiftState(Mode.ONCE, nowMs)
        Mode.ONCE -> if (nowMs - lastTapMs <= DOUBLE_TAP_MS) ShiftState(Mode.LOCKED, nowMs) else ShiftState(Mode.OFF, nowMs)
        Mode.LOCKED -> ShiftState(Mode.OFF, nowMs)
    }

    /** A one-shot capital ends after the next typed character. */
    fun afterChar(): ShiftState = if (mode == Mode.ONCE) copy(mode = Mode.OFF) else this

    fun apply(text: String): String = if (mode == Mode.OFF) text else text.uppercase()

    companion object { const val DOUBLE_TAP_MS = 400L }
}
