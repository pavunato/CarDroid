package com.cardroid.runner

import com.cardroid.lib.airplay.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import kotlin.test.*

/** Asserts the real-iPhone control-channel request order up to RECORD against a fake pairing receiver. */
class AirPlayHandshakeOrderTest {
    private val expected = listOf(
        "POST /pair-setup", "POST /pair-setup", "POST /pair-setup",
        "POST /pair-verify", "POST /pair-verify",
        "POST /auth-setup", "SETUP", "GET /info", "RECORD",
    )

    @Test fun learnedKeyOrder() = assertOrder(pinned = false)
    @Test fun pinnedKeyOrder() = assertOrder(pinned = true)

    private fun assertOrder(pinned: Boolean) = runBlocking {
        val receiverSeed = CarPlayCrypto.random(32)
        val controllerSeed = CarPlayCrypto.random(32)
        val receiver = FakeReceiver(receiverSeed)
        val params = AirPlayParams("127.0.0.1", receiver.port, "test")
        val media = if (pinned) CarPlayMediaOptions("controller-id", controllerSeed, CarPlayCrypto.edPublic(receiverSeed), onReady = {}) else null
        val session = AirPlayHandshakeOrchestrator(
            params,
            addressResolver = { InetSocketAddress("127.0.0.1", receiver.port) },
            mediaOptions = media,
            pairingIdentity = if (pinned) null else "controller-id" to controllerSeed,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { session.run(scope) } // fake receiver hangs up after RECORD
        session.stop()
        scope.cancel()
        receiver.close()
        assertEquals(expected, receiver.requests.toList().take(expected.size))
        assertEquals(1, receiver.requests.take(expected.size).count { it == "GET /info" })
    }

    /** Minimal single-connection AirPlay receiver: HomeKit pair-setup/verify, then canned 200s; closes after RECORD. */
    private class FakeReceiver(private val seed: ByteArray) {
        private val server = ServerSocket(0)
        private val eventServer = ServerSocket(0)
        val port get() = server.localPort
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val id = "fake-receiver".toByteArray()
        private val thread = Thread { runCatching { serve() } }.apply { isDaemon = true; start() }
        private val eventThread = Thread { runCatching { eventServer.accept().use { it.getInputStream().read() } } }.apply { isDaemon = true; start() }

        fun close() { runCatching { server.close() }; runCatching { eventServer.close() }; thread.join(2000) }

        private fun raw(v: BigInteger) = v.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        private fun pad(v: BigInteger) = ByteArray(384).also { val b = raw(v); b.copyInto(it, it.size - b.size) }

        private fun readLine(i: InputStream): String? {
            val sb = ByteArrayOutputStream()
            while (true) { val b = i.read(); if (b < 0) return null; if (b == 0x0A) break; if (b != 0x0D) sb.write(b) }
            return sb.toString(Charsets.ISO_8859_1)
        }

        private fun serve() {
            val s: Socket = server.accept()
            var input: InputStream = s.getInputStream()
            var output: java.io.OutputStream = s.getOutputStream()
            val n = SRP6StandardGroups.rfc5054_3072.n
            val g = BigInteger.valueOf(5)
            val salt = CarPlayCrypto.random(16)
            val x = BigInteger(1, CarPlayCrypto.hash(salt, CarPlayCrypto.hash("Pair-Setup:3939".toByteArray())))
            val v = g.modPow(x, n)
            val k = BigInteger(1, CarPlayCrypto.hash(raw(n), pad(g)))
            val b = BigInteger(1, CarPlayCrypto.random(32))
            val bPub = (k * v + g.modPow(b, n)).mod(n)
            var srpKey = ByteArray(0)
            val ephemeral = CarPlayCrypto.random(32)
            var verifyKey = ByteArray(0)
            var shared = ByteArray(0)
            val xPub = CarPlayCrypto.xPublic(ephemeral)
            val edPub = CarPlayCrypto.edPublic(seed)
            var enableAfterReply = false
            while (true) {
                val line = readLine(input) ?: return
                val parts = line.split(" ")
                var contentLength = 0
                var cseq = "0"
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    val idx = h.indexOf(':')
                    val name = h.substring(0, idx).trim().lowercase()
                    if (name == "content-length") contentLength = h.substring(idx + 1).trim().toInt()
                    if (name == "cseq") cseq = h.substring(idx + 1).trim()
                }
                val body = readExact(input, contentLength)
                val method = parts[0]
                val path = if (method == "POST" || method == "GET") " " + parts[1] else ""
                requests += method + path
                val reply: ByteArray = when ("$method$path") {
                    "POST /pair-setup" -> {
                        val tlv = Tlv8.decode(body)
                        when (tlv.getValue(Tlv8.STATE)[0].toInt()) {
                            1 -> Tlv8.encode(Tlv8.STATE to Tlv8.u8(2), Tlv8.PUBLIC_KEY to pad(bPub), Tlv8.SALT to salt)
                            3 -> {
                                val a = BigInteger(1, tlv.getValue(Tlv8.PUBLIC_KEY))
                                val aPad = pad(a)
                                val u = BigInteger(1, CarPlayCrypto.hash(aPad, pad(bPub)))
                                srpKey = CarPlayCrypto.hash(raw(((a * v.modPow(u, n)).mod(n)).modPow(b, n)))
                                val proof = tlv.getValue(Tlv8.PROOF)
                                Tlv8.encode(Tlv8.STATE to Tlv8.u8(4), Tlv8.PROOF to CarPlayCrypto.hash(aPad, proof, srpKey))
                            }
                            else -> {
                                val key = CarPlayCrypto.hkdf(srpKey, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
                                CarPlayCrypto.open(key, CarPlayCrypto.nonce("PS-Msg05"), tlv.getValue(Tlv8.ENCRYPTED_DATA))
                                val sign = CarPlayCrypto.hkdf(srpKey, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
                                val sub = Tlv8.encode(Tlv8.IDENTIFIER to id, Tlv8.PUBLIC_KEY to edPub,
                                    Tlv8.SIGNATURE to CarPlayCrypto.sign(seed, sign + id + edPub))
                                Tlv8.encode(Tlv8.STATE to Tlv8.u8(6), Tlv8.ENCRYPTED_DATA to
                                    CarPlayCrypto.seal(key, CarPlayCrypto.nonce("PS-Msg06"), sub))
                            }
                        }
                    }
                    "POST /pair-verify" -> {
                        val tlv = Tlv8.decode(body)
                        if (tlv.getValue(Tlv8.STATE)[0].toInt() == 1) {
                            val clientPub = tlv.getValue(Tlv8.PUBLIC_KEY)
                            shared = CarPlayCrypto.shared(ephemeral, clientPub)
                            verifyKey = CarPlayCrypto.hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
                            val sub = Tlv8.encode(Tlv8.IDENTIFIER to id, Tlv8.SIGNATURE to CarPlayCrypto.sign(seed, xPub + id + clientPub))
                            Tlv8.encode(Tlv8.STATE to Tlv8.u8(2), Tlv8.PUBLIC_KEY to xPub, Tlv8.ENCRYPTED_DATA to
                                CarPlayCrypto.seal(verifyKey, CarPlayCrypto.nonce("PV-Msg02"), sub))
                        } else {
                            enableAfterReply = true
                            Tlv8.encode(Tlv8.STATE to Tlv8.u8(4))
                        }
                    }
                    "POST /auth-setup" -> ByteArray(32)
                    "SETUP" -> AirPlayBplist.encode(AirPlayBplist.dict(
                        "eventPort" to AirPlayBplist.int(eventServer.localPort)))
                    else -> ByteArray(0)
                }
                val head = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Length: ${reply.size}\r\n\r\n".toByteArray()
                output.write(head); if (reply.isNotEmpty()) output.write(reply); output.flush()
                if (enableAfterReply) {
                    enableAfterReply = false
                    input = CarPlayRecordInput(s.getInputStream(), CarPlayCrypto.hkdf(shared, "Control-Salt", "Control-Write-Encryption-Key"))
                    output = CarPlayRecordOutput(s.getOutputStream(), CarPlayCrypto.hkdf(shared, "Control-Salt", "Control-Read-Encryption-Key"))
                }
                if (method == "RECORD") { s.close(); return }
            }
        }
    }
}
