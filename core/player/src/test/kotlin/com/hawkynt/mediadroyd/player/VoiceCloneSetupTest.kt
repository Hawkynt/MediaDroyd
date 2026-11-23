package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The original voices set up by the app itself, step by step, each once (#533). */
class VoiceCloneSetupTest {
    @get:Rule
    val temp = TemporaryFolder()

    // Hands that do what is asked on the disk without the network: a fetch writes its files, an unpack makes the
    // place, a venv makes its interpreter; [failing] names a step's first word that fails.
    private inner class FakeHands(private val failing: String? = null) : VoiceCloneSetup.Hands {
        val done = ArrayList<String>()

        override fun fetch(download: NeuralModels.Download, dir: File, progress: (Float) -> Unit): Boolean {
            done += "fetch ${download.folder}"
            if (failing == download.folder) return false
            download.files.forEach { File(dir, it.name).apply { parentFile.mkdirs() }.writeText("x") }
            return true
        }

        override fun unpack(file: File, into: File): Boolean {
            done += "unpack ${file.name} -> ${into.name}"
            into.mkdirs()
            if (file.name.startsWith("cpython")) {
                VoiceCloneSetup.pythonIn(into, VoiceCloneSetup.Os.LINUX).apply { parentFile.mkdirs() }.writeText("")
            }
            return true
        }

        override fun run(command: List<String>, dir: File): String? {
            val what = command.drop(1).joinToString(" ")
            done += "run $what"
            if (failing != null && what.contains(failing)) return null
            if (command.getOrNull(2) == "venv") {
                VoiceCloneSetup.venvPython(File(command[3]), VoiceCloneSetup.Os.LINUX)
                    .apply { parentFile.mkdirs() }.writeText("")
            }
            return if (command.getOrNull(1) == "-c") temp.root.resolve("site/pyopenjtalk").path else ""
        }
    }

    @Test
    fun `Python, the engine's own environment, its packages and its weights, in that order`() {
        val hands = FakeHands()
        val said = ArrayList<String>()
        val python = VoiceCloneSetup.prepare(
            VoiceClones.Engine.XTTS, temp.root, VoiceCloneSetup.Os.LINUX, hands, said::add,
        )
        assertNotNull(python)
        assertEquals("fetch python", hands.done[0])
        assertTrue(hands.done[1].startsWith("unpack cpython-3.10.22"))
        assertTrue(hands.done[2].startsWith("run -m venv"))
        assertTrue(hands.done[3], hands.done[3].contains("torch==2.7.1") && hands.done[3].contains("cu126"))
        assertTrue(hands.done[4].contains("coqui-tts==0.28.0"))
        assertTrue(said.any { it.contains("about 3 GB") })
    }

    @Test
    fun `what was done is not done again, and a failed step is tried again next time`() {
        val failing = FakeHands(failing = "coqui-tts")
        assertNull(VoiceCloneSetup.prepare(VoiceClones.Engine.XTTS, temp.root, VoiceCloneSetup.Os.LINUX, failing) {})
        val again = FakeHands()
        assertNotNull(VoiceCloneSetup.prepare(VoiceClones.Engine.XTTS, temp.root, VoiceCloneSetup.Os.LINUX, again) {})
        // Python, the venv and PyTorch were done the first time; only coqui-tts is left.
        assertEquals(1, again.done.size)
        assertTrue(again.done.single().contains("coqui-tts"))
    }

    @Test
    fun `GPT-SoVITS is set up as its own installer does, its archives unpacked where it reads them`() {
        val hands = FakeHands()
        assertNotNull(
            VoiceCloneSetup.prepare(VoiceClones.Engine.GPT_SOVITS, temp.root, VoiceCloneSetup.Os.LINUX, hands) {},
        )
        val steps = hands.done.joinToString("\n")
        assertTrue(steps, steps.contains("unpack 6d51ffceafbf240145619af4c92408a6e54b7924.zip -> gpt-sovits"))
        assertTrue(steps, steps.contains("-r extra-req.txt --no-deps"))
        assertTrue(steps, steps.contains("unpack pretrained_models.zip -> GPT_SoVITS"))
        assertTrue(steps, steps.contains("unpack G2PWModel.zip -> text"))
        assertTrue(steps, steps.contains("unpack nltk_data.zip -> venv"))
        assertTrue(steps, steps.contains("unpack open_jtalk_dic_utf_8-1.11.tar.gz -> pyopenjtalk"))
    }

    @Test
    fun `OpenVoice's weights are kept as files where the helper reads them`() {
        assertNotNull(
            VoiceCloneSetup.prepare(VoiceClones.Engine.OPENVOICE, temp.root, VoiceCloneSetup.Os.LINUX, FakeHands()) {},
        )
        assertTrue(File(temp.root, "openvoice/checkpoints_v2/converter/checkpoint.pth").isFile)
        assertTrue(File(temp.root, "openvoice/checkpoints_v2/base_speakers/ses/en-newest.pth").isFile)
    }

    @Test
    fun `sizes read as the household reads them`() {
        assertEquals("about 3 GB", VoiceCloneSetup.size(3_000_000_000L))
        assertEquals("about 589 MB", VoiceCloneSetup.size(588_856_634L))
    }
}
