package com.cardroid.lib

/** Real phone battery percentage (0-100), or null when unknown. Injected by the app. */
fun interface PhonePowerProvider {
    fun batteryPercent(): Int?
}

/**
 * Process-wide source for iAP2 PowerUpdate (0xAE01). The app installs the
 * Android BatteryManager-backed provider; with none installed the level is
 * unknown and is simply not reported (nothing is fabricated).
 */
object PhonePower : PhonePowerProvider {
    @Volatile var delegate: PhonePowerProvider = PhonePowerProvider { null }
    override fun batteryPercent(): Int? = delegate.batteryPercent()?.coerceIn(0, 100)
}
