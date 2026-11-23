package com.hawkynt.mediadroyd.player

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * A gzipped tar archive unpacked into a folder (the portable Python the voice-clone helper runs on, VoiceCloneSetup):
 * POSIX ustar entries — files, folders, symbolic links — with GNU's long names and PAX's extended headers, as
 * python-build-standalone's archives hold them, the executable bit kept. Written from POSIX.1-2008's pax format
 * description (rung 3). An entry naming a place outside the folder is refused, and the whole with it.
 */
object TarGz {
    private const val BLOCK = 512

    /** [input] unpacked into [dir]; false where it is no tar or reaches outside [dir]. Blocks. */
    fun extract(input: InputStream, dir: File): Boolean = runCatching {
        val root = dir.canonicalFile.also { it.mkdirs() }
        GZIPInputStream(input.buffered()).use { tar ->
            var longName: String? = null
            var longLink: String? = null
            var pax = emptyMap<String, String>()
            val header = ByteArray(BLOCK)
            while (true) {
                if (!readFully(tar, header)) break
                if (header.all { it.toInt() == 0 }) break
                val size = octal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val data = readData(tar, size)
                when (type) {
                    'L' -> longName = String(data).trimEnd('\u0000')
                    'K' -> longLink = String(data).trimEnd('\u0000')
                    'x' -> pax = paxRecords(data)
                    'g' -> Unit
                    else -> {
                        val prefix = text(header, 345, 155)
                        val plain = text(header, 0, 100).let { if (prefix.isEmpty()) it else "$prefix/$it" }
                        val name = pax["path"] ?: longName ?: plain
                        val link = pax["linkpath"] ?: longLink ?: text(header, 157, 100)
                        longName = null
                        longLink = null
                        pax = emptyMap()
                        val target = File(root, name).canonicalFile
                        check(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
                            "$name lies outside the folder"
                        }
                        val mode = octal(header, 100, 8).toInt()
                        when (type) {
                            '5' -> target.mkdirs()
                            '2' -> {
                                target.parentFile.mkdirs()
                                target.delete()
                                // Windows may not let a program make links; a link there is a copy of what it names.
                                runCatching { Files.createSymbolicLink(target.toPath(), Paths.get(link)) }.onFailure {
                                    val linked = File(target.parentFile, link)
                                    if (linked.isFile) linked.copyTo(target, overwrite = true)
                                }
                            }
                            '0', '\u0000', '7' -> {
                                target.parentFile.mkdirs()
                                target.writeBytes(data)
                                if (mode and 0b001_000_000 != 0) target.setExecutable(true, false)
                            }
                            // Hard links, devices and FIFOs: not in the archives this unpacks.
                            else -> Unit
                        }
                    }
                }
            }
        }
        true
    }.getOrDefault(false)

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var at = 0
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) return false
            at += n
        }
        return true
    }

    // An entry's data, and the padding to the next block skipped.
    private fun readData(input: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        check(readFully(input, data)) { "the archive ends inside an entry" }
        val pad = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        if (pad > 0) check(readFully(input, ByteArray(pad))) { "the archive ends inside an entry" }
        return data
    }

    private fun text(header: ByteArray, at: Int, length: Int): String {
        var end = at
        while (end < at + length && header[end].toInt() != 0) end++
        return String(header, at, end - at, Charsets.UTF_8)
    }

    // A numeric field: octal digits, or GNU's base-256 for large values.
    private fun octal(header: ByteArray, at: Int, length: Int): Long {
        if (header[at].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in at + 1 until at + length) v = (v shl 8) or (header[i].toLong() and 0xFF)
            return v
        }
        return text(header, at, length).trim().ifEmpty { "0" }.toLong(8)
    }

    // PAX records: "<length> <key>=<value>\n", one after the other.
    private fun paxRecords(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var at = 0
        while (at < data.size) {
            val space = (at until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
            val length = String(data, at, space - at).toIntOrNull() ?: break
            val record = String(data, space + 1, at + length - space - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            at += length
        }
        return out
    }
}
