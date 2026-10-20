package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? = Decoder(bytes).decode()

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    private class Decoder(private val bytes: ByteArray) {
        private var table = 0
        private var refSize = 0
        private var references = 0L
        private lateinit var offsets: IntArray
        private lateinit var state: ByteArray
        private lateinit var cache: Array<Any?>
        private lateinit var expandedNodes: LongArray
        private lateinit var expandedBytes: LongArray

        fun decode(): Any? {
            require(bytes.size >= 40) { "bplist: too short" }
            require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }
            val trailer = bytes.size - 32
            val offsetSize = bytes[trailer + 6].toInt() and 0xff
            refSize = bytes[trailer + 7].toInt() and 0xff
            require(offsetSize in 1..8 && refSize in 1..8) { "bplist: invalid integer width" }
            val count = unsigned(trailer + 8L, 8, bytes.size)
            val top = unsigned(trailer + 16L, 8, bytes.size)
            val tableOffset = unsigned(trailer + 24L, 8, bytes.size)
            require(count in 1L..MAX_OBJECTS.toLong()) { "bplist: invalid object count" }
            require(top >= 0 && top < count) { "bplist: invalid top object" }
            require(tableOffset >= magic.size && tableOffset <= trailer &&
                count * offsetSize <= trailer - tableOffset) { "bplist: invalid offset table" }
            table = tableOffset.toInt()
            // Validate all metadata before allocating from a count supplied by the input.
            offsets = IntArray(count.toInt()) { index ->
                val offset = unsigned(tableOffset + index.toLong() * offsetSize, offsetSize, trailer)
                require(offset >= magic.size && offset < tableOffset) { "bplist: invalid object offset" }
                offset.toInt()
            }
            state = ByteArray(offsets.size)
            cache = arrayOfNulls(offsets.size)
            expandedNodes = LongArray(offsets.size)
            expandedBytes = LongArray(offsets.size)
            return readObject(top.toInt(), 0)
        }

        private fun unsigned(offset: Long, size: Int, limit: Int): Long {
            require(size in 1..8 && offset >= 0 && offset <= limit.toLong() - size) { "bplist: truncated integer" }
            var value = 0L
            for (i in 0 until size) value = (value shl 8) or (bytes[offset.toInt() + i].toLong() and 0xffL)
            return value
        }

        private fun range(position: Int, length: Long): Int {
            require(position >= magic.size && length >= 0 && position <= table &&
                length <= table.toLong() - position) { "bplist: truncated object" }
            return length.toInt()
        }

        private fun readObject(index: Int, depth: Int): Any? {
            require(index in offsets.indices) { "bplist: invalid object reference" }
            require(depth <= MAX_DEPTH) { "bplist: nesting limit exceeded" }
            require(state[index].toInt() != 1) { "bplist: cyclic object reference" }
            if (state[index].toInt() == 2) return cache[index]
            state[index] = 1
            var position = offsets[index]
            range(position, 1)
            val marker = bytes[position++].toInt() and 0xff
            val type = marker ushr 4
            val nibble = marker and 15
            var nodes = 1L
            var sizeBytes = 0L

            fun count(): Int {
                if (nibble != 15) return nibble
                range(position, 1)
                val intMarker = bytes[position++].toInt() and 0xff
                require((intMarker ushr 4) == 1 && (intMarker and 15) <= 3) { "bplist: invalid length integer" }
                val width = 1 shl (intMarker and 15)
                range(position, width.toLong())
                val value = unsigned(position.toLong(), width, table)
                position += width
                require(value in 0L..Int.MAX_VALUE.toLong()) { "bplist: invalid length" }
                return value.toInt()
            }

            fun reference(at: Int): Int {
                val ref = unsigned(at.toLong(), refSize, table)
                require(ref >= 0 && ref < offsets.size) { "bplist: invalid object reference" }
                return ref.toInt()
            }

            fun child(ref: Int): Any? {
                val value = readObject(ref, depth + 1)
                nodes += expandedNodes[ref]
                sizeBytes += expandedBytes[ref]
                require(nodes <= MAX_EXPANDED_NODES && sizeBytes <= MAX_DECODED_BYTES) { "bplist: expansion limit exceeded" }
                return value
            }

            val result: Any? = when (type) {
                0 -> when (nibble) {
                    0 -> null
                    8 -> false
                    9 -> true
                    else -> throw IllegalArgumentException("bplist: unsupported primitive")
                }
                1 -> {
                    require(nibble <= 3) { "bplist: integer exceeds 64 bits" }
                    val width = 1 shl nibble
                    range(position, width.toLong())
                    unsigned(position.toLong(), width, table)
                }
                2 -> {
                    require(nibble == 2 || nibble == 3) { "bplist: invalid real width" }
                    val width = 1 shl nibble
                    range(position, width.toLong())
                    val bits = unsigned(position.toLong(), width, table)
                    if (width == 4) Float.fromBits(bits.toInt()) else Double.fromBits(bits)
                }
                3 -> {
                    require(nibble == 3) { "bplist: invalid date width" }
                    range(position, 8)
                    java.util.Date(Math.round((Double.fromBits(unsigned(position.toLong(), 8, table)) + APPLE_EPOCH_SECONDS) * 1000))
                }
                4, 5, 6 -> {
                    val count = count()
                    val length = count.toLong() * if (type == 6) 2 else 1
                    val byteLength = range(position, length)
                    sizeBytes = count.toLong() * if (type == 4) 1 else 2
                    require(sizeBytes <= MAX_DECODED_BYTES) { "bplist: data limit exceeded" }
                    when (type) {
                        4 -> bytes.copyOfRange(position, position + byteLength)
                        5 -> String(bytes, position, byteLength, Charsets.US_ASCII)
                        else -> String(bytes, position, byteLength, Charsets.UTF_16BE)
                    }
                }
                8 -> {
                    require(nibble < 8) { "bplist: UID exceeds 64 bits" }
                    range(position, nibble + 1L)
                    unsigned(position.toLong(), nibble + 1, table)
                }
                10, 12, 13 -> {
                    val count = count()
                    require(count <= MAX_OBJECTS) { "bplist: collection limit exceeded" }
                    val refs = count.toLong() * if (type == 13) 2 else 1
                    references += refs
                    require(references <= MAX_EXPANDED_NODES) { "bplist: reference limit exceeded" }
                    range(position, refs * refSize)
                    if (type == 13) {
                        val dict = LinkedHashMap<String, Any?>(count)
                        for (i in 0 until count) {
                            val key = child(reference(position + i * refSize))
                            require(key is String) { "bplist: dictionary key is not a string" }
                            dict[key] = child(reference(position + (count + i) * refSize))
                        }
                        dict
                    } else {
                        ArrayList<Any?>(count).apply {
                            for (i in 0 until count) add(child(reference(position + i * refSize)))
                        }
                    }
                }
                else -> throw IllegalArgumentException("bplist: unsupported object type")
            }
            expandedNodes[index] = nodes
            expandedBytes[index] = sizeBytes
            cache[index] = result
            state[index] = 2
            return result
        }
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        return byteArrayOf((0x10 or log).toByte()) + raw.copyOfRange(raw.size - size, raw.size)
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()

    // Bound both actual allocations and the logical tree, even when a small DAG shares objects.
    private const val MAX_OBJECTS = 100_000
    private const val MAX_DEPTH = 64
    private const val MAX_EXPANDED_NODES = 200_000L
    private const val MAX_DECODED_BYTES = 16L * 1024 * 1024
    private const val APPLE_EPOCH_SECONDS = 978_307_200.0
}
