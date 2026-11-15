package com.hawkynt.mediadroyd.library

import java.io.Closeable
import java.io.InputStream
import kotlin.math.min

/**
 * Reads a [file] at any position for a decoder that jumps around in it (#484): the sound of a video between its
 * pictures, its index at the end between the samples at the start. The file is read in blocks of [blockSize] bytes and
 * the last [kept] blocks are kept, so reads near each other come from memory and the file is reopened (a document
 * provider call, a network request) only where a block lies elsewhere than the last one ended.
 */
class BlockReader(
    private val file: FsFile,
    private val blockSize: Int = BLOCK_SIZE,
    private val kept: Int = KEPT,
) : Closeable {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(kept, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > kept
    }
    private var stream: InputStream? = null
    private var position = -1L

    /**
     * Up to [size] bytes from byte [at] into [buffer] from [offset]: how many, at most to the end of a block; -1 at the
     * end of the file.
     */
    @Synchronized
    fun read(at: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        val index = at / blockSize
        val block = blocks[index] ?: load(index) ?: return -1
        val within = (at - index * blockSize).toInt()
        if (within >= block.size) return -1
        val count = min(size, block.size - within)
        System.arraycopy(block, within, buffer, offset, count)
        return count
    }

    // Block [index] read from the file, going on where the last one ended or opened anew; null past the end.
    private fun load(index: Long): ByteArray? {
        val start = index * blockSize
        val from = stream?.takeIf { position == start } ?: run {
            stream?.close()
            file.open(start).also {
                stream = it
                position = start
            }
        }
        val block = ByteArray(blockSize)
        var filled = 0
        while (filled < blockSize) {
            val read = from.read(block, filled, blockSize - filled)
            if (read < 0) break
            filled += read
        }
        position += filled
        if (filled == 0) return null
        return (if (filled == blockSize) block else block.copyOf(filled)).also { blocks[index] = it }
    }

    @Synchronized
    override fun close() {
        stream?.close()
        stream = null
        blocks.clear()
    }

    private companion object {
        const val BLOCK_SIZE = 128 * 1024
        // Two megabytes: the places a decoder alternates between, each with room to read on.
        const val KEPT = 16
    }
}
