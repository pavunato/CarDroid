package com.cardroid.app.car.keyboard

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo

/**
 * Virtual-device-only input method, selected for the car session's Virtual Device by the helper (the phone keeps its own
 * keyboard). Android cannot show an IME window on the private car panels, so this service never draws: it hands the
 * focused editor to [CarKeyboard], and the car launcher draws the keys on the car screen.
 */
class CarInputMethodService : InputMethodService() {
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        CarKeyboard.attach(this, currentInputConnection, attribute)
    }

    override fun onFinishInput() {
        CarKeyboard.detach(this)
        super.onFinishInput()
    }

    override fun onDestroy() {
        CarKeyboard.detach(this)
        super.onDestroy()
    }

    override fun onViewClicked(focusChanged: Boolean) { CarKeyboard.editorClicked(this) }

    override fun onEvaluateFullscreenMode() = false
}
