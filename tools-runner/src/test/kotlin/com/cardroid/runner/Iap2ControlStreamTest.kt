package com.cardroid.runner

import com.cardroid.lib.Iap2ControlSession
import com.cardroid.lib.Iap2ControlStream
import com.cardroid.lib.Iap2Frame
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.*

class Iap2ControlStreamTest {
    @Test fun reassemblesSplitAndCoalescedMessages() {
        val first = Iap2ControlSession.build(0x1d01, Iap2ControlSession.param(0, "receiver".encodeToByteArray()))
        val second = Iap2ControlSession.build(0xaa03, Iap2ControlSession.param(0, byteArrayOf(1, 2)))
        for (split in 1 until first.size) {
            val parser = Iap2ControlStream()
            assertTrue(parser.feed(first.copyOfRange(0, split)).isEmpty())
            val messages = parser.feed(first.copyOfRange(split, first.size) + second)
            assertEquals(2, messages.size)
            assertContentEquals(first, messages[0]); assertContentEquals(second, messages[1])
        }
    }
    @Test fun handlesLargestControlMessageAndRejectsMalformedHeaders() {
        val message = Iap2ControlSession.build(0xaa01, ByteArray(65535 - 6) { 0x55 })
        val parser = Iap2ControlStream()
        assertTrue(parser.feed(message.copyOfRange(0, 8192)).isEmpty())
        assertContentEquals(message, parser.feed(message.copyOfRange(8192, message.size)).single())
        assertFailsWith<IOException> { Iap2ControlStream().feed(byteArrayOf(0x40, 0x41, 0, 6, 0, 1)) }
        assertFailsWith<IOException> { Iap2ControlStream().feed(byteArrayOf(0x40, 0x40, 0, 5, 0, 1)) }
    }
    @Test fun linkFrameParserAcceptsFullAdvertisedSixteenBitLimit() {
        for (length in listOf(8193, 65535)) {
            val original = Iap2Frame(0x40, 2, 3, 1, ByteArray(length - 10) { 0x55 })
            val parsed = assertNotNull(Iap2Frame.readFrom(ByteArrayInputStream(original.encode())))
            assertContentEquals(original.payload, parsed.payload)
        }
        assertFailsWith<IllegalArgumentException> { Iap2Frame(0x40, 1, 1, 1, ByteArray(65526)).encode() }
    }
}
