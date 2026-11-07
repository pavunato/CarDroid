package com.cardroid.lib.airplay

/**
 * Playback-driven type-102 audio stream, as a real iPhone does it: nothing at startup, SETUP when
 * media starts, TEARDOWN {streams:[{type:102}]} after sustained silence.  Called only from the
 * single audio capture thread; [setup]/[teardown] serialize on the RTSP control lock themselves.
 */
class AudioStreamLifecycle(
    private val setup: () -> Unit,
    private val teardown: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    @Volatile var active: Boolean = false
        private set

    /** Idempotent: repeated playback-start signals never send a second SETUP. */
    @Synchronized fun ensure(): Boolean {
        if (active) return true
        return try { setup(); active = true; true }
        catch (e: Exception) { log("AirPlay: audio SETUP failed: ${e.javaClass.simpleName}: ${e.message}"); false }
    }

    @Synchronized fun release() {
        if (!active) return
        active = false
        try { teardown() } catch (e: Exception) { log("AirPlay: audio TEARDOWN failed: ${e.javaClass.simpleName}: ${e.message}") }
    }

    companion object {
        /** Never an empty TEARDOWN: that would end video, events and the whole session. */
        fun teardownBody(streamType: Int): ByteArray = AirPlayBplist.encode(AirPlayBplist.dict(
            "streams" to AirPlayBplist.array(AirPlayBplist.dict("type" to AirPlayBplist.int(streamType))),
        ))
    }
}
