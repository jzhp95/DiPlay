package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class BplistCodecBoundsTest {
    @Test fun invalidWidthsAreRejectedBeforeReadingObjects() {
        for (field in listOf(6, 7)) for (width in listOf(0, 9, 255)) {
            val bytes = BplistCodec.encode(true)
            bytes[bytes.size - 32 + field] = width.toByte()
            rejects(bytes)
        }
    }

    @Test fun countsIndexesAndTablesAreValidatedBeforeAllocation() {
        for (count in listOf(0L, -1L, Long.MAX_VALUE, 100_001L)) {
            rejects(metadata(BplistCodec.encode(true), 8, count))
        }
        rejects(metadata(BplistCodec.encode(true), 16, 1))
        rejects(metadata(BplistCodec.encode(true), 16, -1))
        rejects(metadata(BplistCodec.encode(true), 24, Long.MAX_VALUE))
        rejects(metadata(BplistCodec.encode(true), 24, 0))
        val missingTable = BplistCodec.encode(true)
        rejects(metadata(missingTable, 24, (missingTable.size - 32).toLong()))
    }

    @Test fun objectOffsetsAndReferencesCannotReadHeadersOrTrailers() {
        val headerOffset = plist(byteArrayOf(9))
        headerOffset[9] = 0; headerOffset[10] = 0; headerOffset[11] = 0; headerOffset[12] = 0
        rejects(headerOffset)
        rejects(plist(byteArrayOf(0xa1.toByte(), 1)))
        rejects(plist(byteArrayOf(0x4e)))
        rejects(plist(byteArrayOf(0x6e)))
        rejects(plist(byteArrayOf(0x33, 0)))
    }

    @Test fun extendedLengthsMustBeBoundedIntegers() {
        rejects(plist(byteArrayOf(0x4f, 0x50)))
        rejects(plist(byteArrayOf(0x4f, 0x14, 0)))
        rejects(plist(byteArrayOf(0x4f, 0x13) + ByteArray(8) { 0xff.toByte() }))
        rejects(plist(byteArrayOf(0xaf.toByte(), 0x12, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())))
    }

    @Test fun cyclicAndDeepObjectGraphsAreRejectedWithoutStackOverflow() {
        rejects(plist(byteArrayOf(0xa1.toByte(), 0)))
        val nodes = mutableListOf(byteArrayOf(9))
        repeat(70) { nodes += byteArrayOf(0xa1.toByte(), (nodes.size - 1).toByte()) }
        rejects(plist(*nodes.toTypedArray()))
    }

    @Test fun sharedObjectGraphsCannotExpandWithoutABudget() {
        val nodes = mutableListOf(byteArrayOf(9))
        repeat(7) { nodes += byteArrayOf(0xa8.toByte()) + ByteArray(8) { (nodes.size - 1).toByte() } }
        rejects(plist(*nodes.toTypedArray()))
        val text = byteArrayOf(0x5f, 0x11, 0x08, 0) + ByteArray(2048) { 65 }
        val first = byteArrayOf(0xaf.toByte(), 0x10, 100) + ByteArray(100)
        val second = byteArrayOf(0xaf.toByte(), 0x10, 100) + ByteArray(100) { 1 }
        rejects(plist(text, first, second))
    }

    @Test fun validSharedObjectsAndControlValuesRetainTheirMeaning() {
        assertEquals(listOf(true, true), BplistCodec.decode(plist(byteArrayOf(9), byteArrayOf(0xa2.toByte(), 0, 0))))
        val values = linkedMapOf<String, Any?>("name" to "测试", "enabled" to true,
            "streams" to listOf(110L, 111L), "gain" to -3.5)
        assertEquals(values, BplistCodec.decode(BplistCodec.encode(values)))
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, BplistCodec.decode(BplistCodec.encode(bytes)) as ByteArray)
        assertEquals(-1L, BplistCodec.decode(plist(byteArrayOf(0x13) + ByteArray(8) { 0xff.toByte() })))
    }

    @Test fun truncationsFailWithAControlledParseError() {
        val bytes = BplistCodec.encode(mapOf("key" to listOf("value", 42L)))
        for (size in bytes.indices) rejects(bytes.copyOf(size))
    }

    private fun rejects(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
    }

    private fun metadata(bytes: ByteArray, field: Int, value: Long): ByteArray = bytes.copyOf().also {
        write(it, it.size - 32 + field, value, 8)
    }

    private fun plist(vararg nodes: ByteArray): ByteArray {
        val table = 8 + nodes.sumOf { it.size }
        val bytes = ByteArray(table + nodes.size * 4 + 32)
        "bplist00".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        var position = 8
        nodes.forEachIndexed { index, node ->
            node.copyInto(bytes, position)
            write(bytes, table + index * 4, position.toLong(), 4)
            position += node.size
        }
        val trailer = bytes.size - 32
        bytes[trailer + 6] = 4; bytes[trailer + 7] = 1
        write(bytes, trailer + 8, nodes.size.toLong(), 8)
        write(bytes, trailer + 16, (nodes.size - 1).toLong(), 8)
        write(bytes, trailer + 24, table.toLong(), 8)
        return bytes
    }

    private fun write(bytes: ByteArray, offset: Int, value: Long, size: Int) {
        for (index in 0 until size) bytes[offset + index] = (value ushr ((size - index - 1) * 8)).toByte()
    }
}
