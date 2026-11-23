package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** A gzipped tar unpacked as Python's tarfile wrote it (resources/targz/make-input.py). */
class TarGzTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `files, folders, an executable, a link and a long name come out as they went in`() {
        val dir = temp.newFolder()
        val ok = requireNotNull(javaClass.getResourceAsStream("/targz/sample.tar.gz")).use { TarGz.extract(it, dir) }
        assertTrue(ok)
        assertEquals("hello", File(dir, "python/lib/readme.txt").readText())
        assertEquals("long", File(dir, "python/lib/" + "a".repeat(120) + ".py").readText())
        val python = File(dir, "python/bin/python3.10")
        if (!System.getProperty("os.name").startsWith("Windows")) {
            assertTrue(python.canExecute())
            val link = File(dir, "python/bin/python3").toPath()
            assertTrue(Files.isSymbolicLink(link))
            assertEquals(python.readText(), link.toFile().readText())
        }
    }

    @Test
    fun `a name reaching outside the folder is refused`() {
        val dir = temp.newFolder()
        // A tar of one entry "../escape.txt", built by hand: a ustar header, then its data.
        val header = ByteArray(512)
        "../escape.txt".toByteArray().copyInto(header)
        "0000644".toByteArray().copyInto(header, 100)
        "00000000002".toByteArray().copyInto(header, 124)
        header[156] = '0'.code.toByte()
        "        ".toByteArray().copyInto(header, 148)
        val sum = header.sumOf { it.toInt() and 0xFF }
        "%06o\u0000 ".format(sum).toByteArray().copyInto(header, 148)
        val tar = header + "hi".toByteArray().copyOf(512) + ByteArray(1024)
        val gz = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(tar) }
        }.toByteArray()
        assertFalse(TarGz.extract(gz.inputStream(), dir))
        assertFalse(File(dir.parentFile, "escape.txt").exists())
    }
}
