package com.hawkynt.mediadroyd.player

import java.io.File

/**
 * Everything the original voices (VoiceClones, #533) need, set up by the app itself the first time an engine is
 * chosen — on a computer with an NVIDIA graphics card only, nothing fetched elsewhere: a portable Python
 * (python-build-standalone, its digest checked), a virtual environment of the engine's own with its pinned packages,
 * its weights, then the helper (tools/voice-clone/server.py) started on this computer. Each step is done once (a mark
 * in the engine's folder) and none is marked done that failed, so an interrupted setup goes on where it stopped.
 *
 * The steps follow each engine's own installation instructions (rung 3): OpenVoice V2's README (OpenVoice, then
 * MeloTTS, then unidic's dictionary, the V2 checkpoints from its Hugging Face repository), GPT-SoVITS' install.sh
 * (PyTorch for CUDA 12.6, its extra-req.txt and requirements.txt, its pretrained models, G2PW, NLTK's data and
 * OpenJTalk's dictionary), coqui-tts' (the package; it fetches XTTS-v2 on first use, once the household accepted the
 * Coqui Public Model License by choosing it). The engines fetch some voices of their own on first use too.
 */
object VoiceCloneSetup {
    /** The systems the portable Python comes for. */
    enum class Os { LINUX, WINDOWS }

    /** python-build-standalone 20261009, CPython 3.10.22 — the one Python all three engines install on. */
    private const val PYTHON_SOURCE = "https://github.com/astral-sh/python-build-standalone/releases/download/20261009"

    /** The portable Python's archive for [os]. */
    fun python(os: Os): NeuralModels.Download = when (os) {
        Os.LINUX -> NeuralModels.Download(
            "python",
            listOf(
                NeuralModels.File(
                    "cpython-3.10.22+20261009-x86_64-unknown-linux-gnu-install_only.tar.gz", 43_778_272L,
                    "1780de9ddda04e956d2a4a0ad09b7f5667d92f4f12cd0881ec939de5d542903f",
                ),
            ),
            source = PYTHON_SOURCE,
        )
        Os.WINDOWS -> NeuralModels.Download(
            "python",
            listOf(
                NeuralModels.File(
                    "cpython-3.10.22+20261009-x86_64-pc-windows-msvc-install_only.tar.gz", 39_573_204L,
                    "cb023e83e42d8937ba77c7cf83641083c3b81e88e1226e1c694a347ae1924d54",
                ),
            ),
            source = PYTHON_SOURCE,
        )
    }

    /** The portable Python's interpreter under the folder it was unpacked into. */
    fun pythonIn(dir: File, os: Os): File =
        if (os == Os.WINDOWS) File(dir, "python/python.exe") else File(dir, "python/bin/python3")

    /** A virtual environment's interpreter. */
    fun venvPython(venv: File, os: Os): File =
        if (os == Os.WINDOWS) File(venv, "Scripts/python.exe") else File(venv, "bin/python")

    /** Where a fetched archive is unpacked: a folder under the engine's, the venv itself, or a package's folder. */
    sealed interface Into {
        /** [path] under the engine's folder. */
        data class Engine(val path: String) : Into

        /** The virtual environment's own folder (sys.prefix). */
        data object Venv : Into

        /** The folder of the installed Python package [module]. */
        data class Package(val module: String) : Into
    }

    /** One step of an engine's setup; [label] what the household reads, [bytes] about how much it fetches. */
    sealed interface Step {
        val id: String
        val label: String
        val bytes: Long

        /** [download] fetched (its digest checked) and, where [into] names a place, unpacked there. */
        data class Fetch(
            override val id: String,
            override val label: String,
            val download: NeuralModels.Download,
            val into: Into? = null,
        ) : Step {
            override val bytes: Long get() = download.bytes
        }

        /** The engine's interpreter run with [args] in the folder [dir] (under the engine's; "" for its own). */
        data class Run(
            override val id: String,
            override val label: String,
            override val bytes: Long,
            val args: List<String>,
            val dir: String = "",
        ) : Step
    }

    private const val TORCH_INDEX = "https://download.pytorch.org/whl/cu126"
    private const val GB = 1_000_000_000L
    private const val MB = 1_000_000L

    private fun pip(id: String, label: String, bytes: Long, vararg args: String, dir: String = "") =
        Step.Run(id, label, bytes, listOf("-m", "pip", "install", "--disable-pip-version-check", *args), dir)

    // PyTorch with CUDA 12.6 and the NVIDIA libraries it brings: most of what an engine fetches.
    private val torch = pip("torch", "PyTorch for the graphics card", 3 * GB,
        "torch==2.7.1", "torchaudio==2.7.1", "--index-url", TORCH_INDEX)

    private const val OPENVOICE_REPO =
        "https://huggingface.co/myshell-ai/OpenVoiceV2/resolve/f36e7edfe1684461a8343844af60babc2efbb727"
    private const val SOVITS_REPO =
        "https://huggingface.co/XXXXRT/GPT-SoVITS-Pretrained/resolve/0c47645e02a7bc3688d7b263b0042c81e3cd82cd"

    /** GPT-SoVITS' source at the commit its installation was written from, unpacked under the engine's folder. */
    const val SOVITS_SOURCE = "GPT-SoVITS-6d51ffceafbf240145619af4c92408a6e54b7924"

    private fun single(folder: String, source: String, name: String, bytes: Long, sha256: String) =
        NeuralModels.Download(folder, listOf(NeuralModels.File(name, bytes, sha256)), source = source)

    /** The steps setting [engine] up, in order. */
    fun steps(engine: VoiceClones.Engine): List<Step> = when (engine) {
        VoiceClones.Engine.XTTS -> listOf(
            torch,
            pip("coqui-tts", "coqui-tts for XTTS-v2", 400 * MB, "coqui-tts==0.28.0"),
        )
        VoiceClones.Engine.OPENVOICE -> listOf(
            torch,
            pip("openvoice", "OpenVoice V2", 300 * MB,
                "https://github.com/myshell-ai/OpenVoice/archive/74a1d147b17a8c3092dd5430504bd83ef6c7eb23.zip" +
                    "#sha256=d08cbc84f4ec7abc76f9dddb5bbb221e906e49cfe5febd37133152bdeacb8be4"),
            pip("melotts", "MeloTTS, the voices OpenVoice turns", 500 * MB,
                "https://github.com/myshell-ai/MeloTTS/archive/209145371cff8fc3bd60d7be902ea69cbdb7965a.zip" +
                    "#sha256=3edf1b2ab60e777691ab2ec5fba837e278f60761c296bc5d753da5ab8ab5754e"),
            Step.Run("unidic", "MeloTTS's Japanese dictionary", 600 * MB, listOf("-m", "unidic", "download")),
            Step.Fetch(
                "checkpoints",
                "OpenVoice V2's tone colour converter",
                NeuralModels.Download(
                    "checkpoints_v2",
                    listOf(
                        NeuralModels.File("converter/checkpoint.pth", 131_320_490L,
                            "9652c27e92b6b2a91632590ac9962ef7ae2b712e5c5b7f4c34ec55ee2b37ab9e"),
                        NeuralModels.File("converter/config.json", 838L,
                            "9dfff60350b8c63f2c664efd92a61b2516efb22671466960f0e5dfebd881fa47"),
                    ) + OPENVOICE_SES.map { (name, size, sha) ->
                        NeuralModels.File("base_speakers/ses/$name", size, sha)
                    },
                    source = OPENVOICE_REPO,
                ),
            ),
        )
        VoiceClones.Engine.GPT_SOVITS -> listOf(
            Step.Fetch(
                "source", "GPT-SoVITS",
                single(
                    "source", "https://github.com/RVC-Boss/GPT-SoVITS/archive",
                    "6d51ffceafbf240145619af4c92408a6e54b7924.zip", 6_907_495L,
                    "f080219a0f53c5c6b680fd319a5fa570ff5d4065517e41f28151fa2d47267aef",
                ),
                Into.Engine(""),
            ),
            pip("torch", "PyTorch for the graphics card", 3 * GB, "torch", "torchcodec", "--index-url", TORCH_INDEX),
            pip(
                "extra", "GPT-SoVITS' extra packages", 50 * MB, "-r", "extra-req.txt", "--no-deps",
                dir = SOVITS_SOURCE,
            ),
            pip("requirements", "GPT-SoVITS' packages", 1 * GB, "-r", "requirements.txt", dir = SOVITS_SOURCE),
            Step.Fetch(
                "pretrained", "GPT-SoVITS' pretrained models",
                single("pretrained", SOVITS_REPO, "pretrained_models.zip", 4_563_188_367L,
                    "66274394318cbf134b78d0d5aeeccb73e96f5d43cf6876ac43560a972cb1f3fc"),
                Into.Engine("$SOVITS_SOURCE/GPT_SoVITS"),
            ),
            Step.Fetch(
                "g2pw", "GPT-SoVITS' Chinese pronunciation",
                single("g2pw", SOVITS_REPO, "G2PWModel.zip", 588_856_634L,
                    "46292be0374a49308069233cd5c147ae4c41806558e4781a2467a31a4d8099da"),
                Into.Engine("$SOVITS_SOURCE/GPT_SoVITS/text"),
            ),
            Step.Fetch(
                "nltk", "NLTK's English data",
                single("nltk", SOVITS_REPO, "nltk_data.zip", 9_924_448L,
                    "eb3ec26ace3f9ccbb08a6d333e26f0941c47e230ece0717dc992bdb7e99808dd"),
                Into.Venv,
            ),
            Step.Fetch(
                "openjtalk", "OpenJTalk's Japanese dictionary",
                single("openjtalk", SOVITS_REPO, "open_jtalk_dic_utf_8-1.11.tar.gz", 23_646_843L,
                    "fe6ba0e43542cef98339abdffd903e062008ea170b04e7e2a35da805902f382a"),
                Into.Package("pyopenjtalk"),
            ),
        )
    }

    /** OpenVoice V2's base speakers' tone colours: file, size, SHA-256 (its Hugging Face repository, as above). */
    private val OPENVOICE_SES = listOf(
        Triple("en-au.pth", 1701L, "5e9782233deef51fc5289d05ad4dd4ce12b196e282eccf6b6db6256bbd02daaa"),
        Triple("en-br.pth", 1701L, "2bf5a88025cfd10473b25d65d5c0e608338ce4533059c5f9a3383e69c812d389"),
        Triple("en-default.pth", 1783L, "e4139de3bc2ea162f45a5a5f9559b710686c9689749b5ab8945ee5e2a082d154"),
        Triple("en-india.pth", 1701L, "ad03d946757e95fe9e13239aa4b11071d98f22316f604f34b1a0b4bdf41cda48"),
        Triple("en-newest.pth", 1692L, "6a3798229b1114f0e9cc137b33211809def7dda5a8a9398d5a112c0b42699177"),
        Triple("en-us.pth", 1701L, "0d092d4af0815a4bfbc6105b65621ab68dc4c61b2f55044d8a66968a34947c32"),
        Triple("es.pth", 1692L, "b8cece8853fb75b9f5217a1f5cda9807bac92a3e4c4547fc651e404d05deff63"),
        Triple("fr.pth", 1692L, "8a01f6d30a73efa368c288a542a522a2bcdd4e2ec5589d8646b307cf8e2ad9ae"),
        Triple("jp.pth", 1692L, "7b645ff428de4a57a22122318968f1e6127ac81fda2e2aa66062deccd3864416"),
        Triple("kr.pth", 1692L, "f501479d6072741a396725bec79144653e9f4a5381b85901e29683aa169795df"),
        Triple("zh.pth", 1692L, "2b353de562700c13faacf096ecfc0adcafd26e6704a9feef572be1279714e031"),
    )

    /** What a setup needs of the platform: fetching, unpacking and running, each telling whether it worked. */
    interface Hands {
        /** [download]'s files into [dir], their digests checked, [progress] 0 … 1. */
        fun fetch(download: NeuralModels.Download, dir: File, progress: (Float) -> Unit): Boolean

        /** The archive [file] (zip, or tar.gz) unpacked into [into]. */
        fun unpack(file: File, into: File): Boolean

        /** [command] run in [dir] to its end: its output, or null where it failed. */
        fun run(command: List<String>, dir: File): String?
    }

    /**
     * Sets up [engine] under [root] (the app's voice-clone folder) for [os] through [hands], telling [say] each step as
     * it starts ("Setting up the original voices: PyTorch for the graphics card, about 3 GB…"); the engine's
     * interpreter where all its steps are done, null where one failed. Steps marked done before are not done again.
     */
    fun prepare(
        engine: VoiceClones.Engine,
        root: File,
        os: Os,
        hands: Hands,
        say: (String) -> Unit,
    ): File? {
        val pythonDir = File(root, "python")
        val python = pythonIn(pythonDir, os)
        val engineDir = File(root, engine.id)
        val marks = File(engineDir, ".done").apply { mkdirs() }
        if (!python.isFile) {
            say("Setting up the original voices: Python…")
            val download = python(os)
            val archives = File(root, "downloads")
            if (!hands.fetch(download, archives) { }) return null
            val archive = File(archives, download.files.single().name)
            if (!hands.unpack(archive, pythonDir)) return null
            archive.delete()
        }
        val venv = File(engineDir, "venv")
        val interpreter = venvPython(venv, os)
        if (!interpreter.isFile) {
            say("Setting up the original voices: ${engine.label}…")
            hands.run(listOf(python.path, "-m", "venv", venv.path), engineDir) ?: return null
        }
        for (step in steps(engine)) {
            val mark = File(marks, step.id)
            if (mark.isFile) continue
            say("Setting up the original voices: ${step.label}, ${size(step.bytes)}…")
            val ok = when (step) {
                is Step.Run -> hands.run(listOf(interpreter.path) + step.args, File(engineDir, step.dir)) != null
                is Step.Fetch -> fetch(step, engineDir, venv, interpreter, hands)
            }
            if (!ok) return null
            mark.writeText("")
        }
        return interpreter
    }

    private fun fetch(step: Step.Fetch, engineDir: File, venv: File, interpreter: File, hands: Hands): Boolean {
        val into = step.into
        // Weights kept as they are (no place to unpack them) go to the folder their download names.
        val target = if (into == null) File(engineDir, step.download.folder) else File(engineDir, "downloads")
        if (!hands.fetch(step.download, target) { }) return false
        if (into == null) return true
        val archive = File(target, step.download.files.single().name)
        val place = when (into) {
            is Into.Engine -> File(engineDir, into.path)
            Into.Venv -> venv
            is Into.Package -> hands.run(
                listOf(
                    interpreter.path, "-c",
                    "import os, ${into.module}; print(os.path.dirname(${into.module}.__file__))",
                ),
                engineDir,
            )?.trim()?.takeIf { it.isNotEmpty() }?.let(::File) ?: return false
        }
        val ok = hands.unpack(archive, place)
        archive.delete()
        return ok
    }

    /** About how much [bytes] is, as the household reads it. */
    fun size(bytes: Long): String = when {
        bytes >= GB -> "about ${(bytes + GB / 2) / GB} GB"
        else -> "about ${maxOf(1, (bytes + MB / 2) / MB)} MB"
    }
}
