package com.cardroid.runner

import com.cardroid.lib.airplay.AirPlayBplist
import com.cardroid.lib.airplay.AirPlayHandshakeOrchestrator
import com.cardroid.lib.airplay.AudioCodec
import com.cardroid.lib.airplay.AudioProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class IphoneAudioSetupTest {
    @Test
    fun mediaAudioSetupIsByteIdenticalToRealIphone() {
        // Raw DiPlay trace of a real iPhone18,4 / iOS 26.7.1 starting music: SETUP cseq 116, 234 B.
        val iphone = ("62706c6973743030d101025773747265616d73a103d80405060708090a0b0c0d0e0f101112135e617564696f4c617465" +
            "6e63794d735f101273747265616d436f6e6e656374696f6e4944537370665b636f6e74726f6c506f727454747970655b" +
            "617564696f466f726d61745f101e737570706f72747348696768416363757261637954696d657374616d707359617564" +
            "696f547970651103e8137a10e4c11fbe4f8411040011fcd81066120080000009556d65646961080b131526354a4e5a5f" +
            "6b8c9699a2a5a8aaafb000000000000001010000000000000014000000000000000000000000000000b6").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // /info lists 100 ms for t102 media; the iPhone still declares 1000 ms.
        val profile = AudioProfile(
            streamType = AudioProfile.STREAM_TYPE_MAIN_HIGH_AUDIO, audioType = "media",
            formatBit = 0x800000L, sampleRate = 48_000, latencyMs = 100, codec = AudioCodec.AAC_LC,
        )
        val body = AirPlayBplist.encode(AirPlayBplist.dict("streams" to AirPlayBplist.ArrayV(listOf(
            AirPlayHandshakeOrchestrator.iphoneAudioStream(profile, 8795781590366965636L, 64728)))))
        assertEquals(iphone.toList(), body.toList())
    }
}
