package com.hawkynt.mediadroyd.library

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockReaderTest {
    // A file of [size] bytes (byte i is i % 251) that counts how often it is opened.
    private class CountingFile(private val size: Int) : FsFile {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        var opens = 0
        override val name = "e1.mp4"
        override val path = "content://e1.mp4"
        override val isDirectory = false
        override val parentFile: FsFile? = null
        override fun length() = size.toLong()
        override fun lastModified() = 0L
        override fun listFiles(): List<FsFile>? = null
        override fun child(name: String): FsFile = error("not a folder")
        override fun open(offset: Long): InputStream {
            opens++
            return ByteArrayInputStream(bytes, offset.toInt(), size - offset.toInt())
        }
    }

    private fun BlockReader.readFully(at: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val read = read(at + filled, out, filled, size - filled)
            if (read < 0) break
            filled += read
        }
        return out.copyOf(filled)
    }

    @Test
    fun `reads give the file's bytes wherever they start, and nothing past its end`() {
        val file = CountingFile(10_000)
        val reader = BlockReader(file, blockSize = 1024, kept = 4)
        assertArrayEquals(file.bytes.copyOfRange(1000, 3500), reader.readFully(1000, 2500))
        assertArrayEquals(file.bytes.copyOfRange(9_990, 10_000), reader.readFully(9_990, 100))
        assertEquals(-1, reader.read(10_000, ByteArray(10), 0, 10))
        assertEquals(0, reader.read(5, ByteArray(10), 0, 0))
    }

    @Test
    fun `sound read between pictures opens the file once per block, not once per jump`() {
        // Given a video whose sound and picture alternate in small pieces
        val file = CountingFile(64 * 1024)
        val reader = BlockReader(file, blockSize = 16 * 1024, kept = 4)
        // When the sound is read packet by packet, skipping the picture between them
        var at = 0L
        while (at + 300 < file.length()) {
            assertArrayEquals(file.bytes.copyOfRange(at.toInt(), at.toInt() + 300), reader.readFully(at, 300))
            at += 700
        }
        // Then the file is opened once, read on from block to block
        assertEquals(1, file.opens)
    }

    @Test
    fun `reads going back and forth between two places are answered from memory`() {
        // Given an index at the end of a file read alternately with its samples at the start
        val file = CountingFile(1024 * 1024)
        val reader = BlockReader(file, blockSize = 4096, kept = 8)
        repeat(50) { i ->
            reader.readFully(1024L * 1024 - 2048 + i * 8, 8)
            reader.readFully(i * 64L, 64)
        }
        // Then each of the two places is fetched once
        assertEquals(2, file.opens)
    }
}
