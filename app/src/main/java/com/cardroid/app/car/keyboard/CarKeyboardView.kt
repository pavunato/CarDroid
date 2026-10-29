package com.cardroid.app.car.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardroid.app.CarDroidPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** On-screen keyboard drawn by the car launcher for the focused car-panel editor (see [CarKeyboard]). */
@Composable
fun CarKeyboardView(action: EnterAction, onHide: () -> Unit, modifier: Modifier = Modifier) {
    var page by remember { mutableStateOf(Page.LETTERS) }
    var shift by remember { mutableStateOf(ShiftState()) }
    Column(modifier.fillMaxWidth().background(CarDroidPalette.Dock).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CarKeyboardLayout.rows(page).forEach { row ->
            Row(Modifier.widthIn(max = 920.dp).fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val label = when (key) {
                        is Key.Char -> if (page == Page.LETTERS) shift.apply(key.value) else key.value
                        Key.Shift -> if (shift.mode == ShiftState.Mode.LOCKED) "⇪" else "⇧"
                        Key.Backspace -> "⌫"
                        Key.Symbols -> "?123"
                        Key.Letters -> "ABC"
                        Key.Space -> "space"
                        Key.Enter -> action.label
                        Key.Hide -> "⌄"
                    }
                    val highlighted = key == Key.Enter || (key == Key.Shift && shift.mode != ShiftState.Mode.OFF)
                    KeyCap(label, highlighted, Modifier.weight(CarKeyboardLayout.weight(key)), repeat = key == Key.Backspace) {
                        when (key) {
                            is Key.Char -> {
                                CarKeyboard.type(if (page == Page.LETTERS) shift.apply(key.value) else key.value)
                                shift = shift.afterChar()
                            }
                            Key.Shift -> shift = shift.tap(System.currentTimeMillis())
                            Key.Backspace -> CarKeyboard.backspace()
                            Key.Symbols -> page = Page.SYMBOLS
                            Key.Letters -> page = Page.LETTERS
                            Key.Space -> CarKeyboard.type(" ")
                            Key.Enter -> { CarKeyboard.enter(); if (action.hidesKeyboard) onHide() }
                            Key.Hide -> onHide()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, highlighted: Boolean, modifier: Modifier, repeat: Boolean, onPress: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val scope = rememberCoroutineScope()
    val bg = when {
        pressed -> CarDroidPalette.Mint.copy(alpha = 0.45f)
        highlighted -> CarDroidPalette.Mint.copy(alpha = 0.22f)
        else -> CarDroidPalette.SurfaceHigh
    }
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(bg)
        .pointerInput(repeat) {
            // Fire on touch-down (typing feels immediate over the car link); Backspace repeats while held.
            awaitEachGesture {
                awaitFirstDown()
                pressed = true
                press()
                val repeater = if (repeat) scope.launch { delay(450); while (true) { press(); delay(60) } } else null
                do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed })
                repeater?.cancel()
                pressed = false
            }
        }, contentAlignment = Alignment.Center) {
        Text(label, color = if (highlighted) CarDroidPalette.Mint else CarDroidPalette.Text, fontSize = 20.sp, maxLines = 1)
    }
}
