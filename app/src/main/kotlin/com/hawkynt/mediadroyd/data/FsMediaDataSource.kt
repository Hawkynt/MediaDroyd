package com.hawkynt.mediadroyd.data

import android.media.MediaDataSource
import com.hawkynt.mediadroyd.library.BlockReader
import com.hawkynt.mediadroyd.library.FsFile

/**
 * A file on any source for Android's MediaMetadataRetriever and MediaExtractor, which read at random positions: read in
 * blocks, the last ones kept (BlockReader, #484), so the jumps between a video's sound and pictures come from memory.
 */
class FsMediaDataSource(private val file: FsFile) : MediaDataSource() {
    private val reader = BlockReader(file)

    override fun readAt(at: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.read(at, buffer, offset, size)

    override fun getSize(): Long = file.length().takeIf { it > 0 } ?: -1

    override fun close() = reader.close()
}
