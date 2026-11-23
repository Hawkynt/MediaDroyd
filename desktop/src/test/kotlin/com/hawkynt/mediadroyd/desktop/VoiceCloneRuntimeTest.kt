package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.player.VoiceClones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The original voices' helper set up and run by the app itself (#533). */
class VoiceCloneRuntimeTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `without an NVIDIA graphics card nothing is fetched and the household is told why`() {
        val said = ArrayList<String>()
        val runtime = VoiceCloneRuntime(temp.root, hasGpu = { false })
        assertNull(runtime.helper(VoiceClones.Engine.XTTS, said::add))
        assertTrue(said.single().contains("NVIDIA"))
        assertEquals(emptyList<String>(), temp.root.list()!!.toList())
    }

    // An interpreter on this machine to run the carried helper with, as the portable one would.
    private val python = listOf("/usr/bin/python3", "/usr/local/bin/python3").map(::File).firstOrNull { it.canExecute() }

    @Test
    fun `the carried helper is started on this computer, answers, and is stopped`() {
        assumeTrue("no python3 here to run the helper", python != null)
        val runtime = VoiceCloneRuntime(temp.root, hasGpu = { true }, startupMs = 20_000)
        val address = runtime.launch(VoiceClones.Engine.XTTS, python!!)
        assertNotNull(address)
        assertTrue(address!!.startsWith("http://127.0.0.1:"))
        // Without the engines installed and without a graphics card, the helper says so: the app then speaks as before.
        val health = VoiceClones.Client(address).health()!!
        assertFalse(health.cuda)
        assertTrue(File(temp.root, "server.py").isFile)
        runtime.stop()
        assertFalse(VoiceCloneRuntime.answers("$address/health", 1500))
    }

    @Test
    fun `a zip entry reaching outside its folder is refused`() {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("../escape.txt"))
                zip.write("x".toByteArray())
            }
        }.toByteArray()
        val dir = temp.newFolder("in")
        assertFalse(VoiceCloneRuntime.unzip(bytes.inputStream(), dir))
        assertFalse(File(temp.root, "escape.txt").exists())
    }
}
