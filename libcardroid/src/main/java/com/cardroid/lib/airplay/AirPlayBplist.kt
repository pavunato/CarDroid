package com.cardroid.lib.airplay

import java.io.ByteArrayOutputStream

/**
 * Minimal Apple binary plist v1.0 encoder/decoder for the fields AirPlay
 * SETUP / command bodies need.
 *
 * Supports: dict<String, Value>, String, Int (small + long), Data (bytes),
 * Array<Value>. NOT a complete bplist impl -- intentionally narrow.
 *
 * Layout (per https://en.wikipedia.org/wiki/Property_list#Binary):
 *   Magic   "bplist00" (8 bytes)
 *   Objects ... variable, each prefixed by a marker byte
 *   Offset table (one entry per object, offsetSize bytes each)
 *   Trailer (32 bytes):
 *     6 bytes  unused
 *     1 byte   offsetIntSize  (size of each offset in the offset table)
 *     1 byte   objectRefSize  (size of each object ref inside containers)
 *     8 bytes  numObjects     (big-endian)
 *     8 bytes  topObject      (big-endian, usually 0 = the root dict)
 *     8 bytes  offsetTableOffset  (big-endian, absolute file offset)
 */
object AirPlayBplist {

    sealed class Value
    data class StringV(val s: String) : Value()
    data class IntV(val v: Long) : Value()
    data class DataV(val b: ByteArray) : Value()
    data class BoolV(val v: Boolean) : Value()
    data class DictV(val m: LinkedHashMap<String, Value>) : Value()
    data class ArrayV(val list: List<Value>) : Value()

    fun dict(vararg pairs: Pair<String, Value>): DictV = DictV(LinkedHashMap(pairs.toMap()))
    fun str(s: String): StringV = StringV(s)
    fun int(v: Long): IntV = IntV(v)
    fun int(v: Int): IntV = IntV(v.toLong())
    fun data(b: ByteArray): DataV = DataV(b)
    fun bool(v: Boolean): BoolV = BoolV(v)
    fun array(vararg vs: Value): ArrayV = ArrayV(vs.toList())

    /**
     * Shared like an iPhone's encoder: numbers, booleans and short strings (Apple's tagged-pointer
     * strings, at most [SHARED_STRING_MAX] chars) are written once and referenced again; longer
     * strings are written at every use and containers are never shared. Measured from a real
     * iPhone's 287 B `modesChanged` (DiPlay trace): `entity` once, `appStateID` x3,
     * `resourceID`/`permanentEntity` x2; that rule reproduces its size byte-for-byte.
     */
    private fun shared(v: Value): Boolean = when (v) {
        is IntV, is BoolV -> true
        is StringV -> v.s.length <= SHARED_STRING_MAX && v.s.all { it.code < 128 }
        else -> false
    }

    const val SHARED_STRING_MAX = 9

    private class Node(val value: Value, val children: IntArray)

    fun encode(root: Value): ByteArray {
        // Walk the tree in iPhone order: container, its keys, then its values (depth first).
        val objects = mutableListOf<Node?>()
        val sharedRef = HashMap<Value, Int>()
        fun visit(v: Value): Int {
            if (shared(v)) sharedRef[v]?.let { return it }
            val idx = objects.size
            objects.add(null)
            if (shared(v)) sharedRef[v] = idx
            val children = when (v) {
                // dict has key refs then value refs; key strings are objects too.
                is DictV -> {
                    val keys = v.m.keys.map { visit(StringV(it)) }
                    val values = v.m.values.map { visit(it) }
                    (keys + values).toIntArray()
                }
                is ArrayV -> v.list.map { visit(it) }.toIntArray()
                else -> IntArray(0)
            }
            objects[idx] = Node(v, children)
            return idx
        }
        visit(root)

        val refSize = byteSizeFor(objects.size)
        val body = ByteArrayOutputStream()
        body.write("bplist00".toByteArray(Charsets.US_ASCII))

        val offsets = IntArray(objects.size)
        for ((i, node) in objects.withIndex()) {
            offsets[i] = body.size()
            writeObject(body, node!!, refSize)
        }

        val offsetTableOffset = body.size().toLong()
        val offIntSize = byteSizeFor(offsetTableOffset.toInt().coerceAtLeast(body.size()))
        for (off in offsets) writeUIntBE(body, off.toLong(), offIntSize)

        // Trailer
        body.write(ByteArray(6))            // unused
        body.write(offIntSize)
        body.write(refSize)
        writeULongBE(body, objects.size.toLong())
        writeULongBE(body, 0L)              // top object index = root
        writeULongBE(body, offsetTableOffset)
        return body.toByteArray()
    }

    private fun writeObject(out: ByteArrayOutputStream, node: Node, refSize: Int) {
        when (val v = node.value) {
            is StringV -> {
                val bytes = v.s.toByteArray(Charsets.US_ASCII)
                // If the string is pure ASCII, use 0x5_ (ASCII string).
                // Otherwise fall back to 0x6_ (UTF-16 BE).
                if (v.s.all { it.code < 128 }) {
                    writeLengthPrefix(out, 0x50, bytes.size)
                    out.write(bytes)
                } else {
                    val utf16 = v.s.toByteArray(Charsets.UTF_16BE)
                    writeLengthPrefix(out, 0x60, v.s.length)
                    out.write(utf16)
                }
            }
            is BoolV -> out.write(if (v.v) 0x09 else 0x08)
            is IntV -> {
                val n = v.v
                // Choose width: 1, 2, 4, or 8 bytes (signed conceptually, but
                // bplist encodes unsigned for 1/2/4 and signed for 8).
                // 1/2/4-byte bplist ints are unsigned (an iPhone writes port 64728 as 0x11 FCD8).
                val width = when {
                    n < 0L -> 8 // two's-complement signed bplist integer
                    n <= 0xFFL -> 1
                    n <= 0xFFFFL -> 2
                    n <= 0xFFFF_FFFFL -> 4
                    else -> 8
                }
                val log2 = when (width) { 1 -> 0; 2 -> 1; 4 -> 2; else -> 3 }
                out.write(0x10 or log2)
                writeUIntBE(out, n, width)
            }
            is DataV -> {
                writeLengthPrefix(out, 0x40, v.b.size)
                out.write(v.b)
            }
            is DictV -> {
                writeLengthPrefix(out, 0xD0, v.m.size)
                for (ref in node.children) writeUIntBE(out, ref.toLong(), refSize)
            }
            is ArrayV -> {
                writeLengthPrefix(out, 0xA0, v.list.size)
                for (ref in node.children) writeUIntBE(out, ref.toLong(), refSize)
            }
        }
    }

    private fun writeLengthPrefix(out: ByteArrayOutputStream, marker: Int, length: Int) {
        if (length < 15) {
            out.write(marker or length)
        } else {
            out.write(marker or 0x0F)
            // length is itself encoded as an int object marker (0x1_) +
            // big-endian value.
            val width = when {
                length < 0x100 -> 1
                length < 0x10000 -> 2
                else -> 4
            }
            val log2 = when (width) { 1 -> 0; 2 -> 1; else -> 2 }
            out.write(0x10 or log2)
            writeUIntBE(out, length.toLong(), width)
        }
    }

    private fun writeUIntBE(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in size - 1 downTo 0) {
            out.write(((value ushr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeULongBE(out: ByteArrayOutputStream, value: Long) = writeUIntBE(out, value, 8)

    private fun byteSizeFor(maxValue: Int): Int = when {
        maxValue < 0x100 -> 1
        maxValue < 0x10000 -> 2
        maxValue < 0x1000000 -> 3
        else -> 4
    }
}
