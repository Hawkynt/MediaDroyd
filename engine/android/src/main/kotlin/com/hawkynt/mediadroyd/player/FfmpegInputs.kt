package com.hawkynt.mediadroyd.player

import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer
import com.hawkynt.mediadroyd.library.BlockReader
import com.hawkynt.mediadroyd.library.FsFile
import java.io.RandomAccessFile

/**
 * Files as FFmpeg's demuxers read them: a local file at random, a network file in blocks, the last ones kept
 * (BlockReader), so reading one stream of an interleaved file does not open it again at every jump (#491).
 */
object FfmpegInputs {

    fun local(file: RandomAccessFile): FfmpegDemuxer.Input = object : FfmpegDemuxer.Input {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = file.read(buffer, offset, length)

        override fun seek(position: Long): Long {
            file.seek(position)
            return position
        }

        override fun length(): Long = file.length()
    }

    fun remote(file: FsFile): FfmpegDemuxer.Input = object : FfmpegDemuxer.Input {
        private val reader = BlockReader(file)
        private var position = 0L

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = reader.read(position, buffer, offset, length)
            if (read > 0) position += read
            return read
        }

        override fun seek(position: Long): Long {
            this.position = position
            return position
        }

        override fun length(): Long = file.length().takeIf { it > 0 } ?: -1
    }
}
