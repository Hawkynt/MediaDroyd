package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.player.ModelFolder
import com.hawkynt.mediadroyd.player.NeuralModels
import com.hawkynt.mediadroyd.player.TarGz
import com.hawkynt.mediadroyd.player.VoiceCloneSetup
import com.hawkynt.mediadroyd.player.VoiceClones
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The original voices' helper run by the app itself (#533): nothing for the household to install or start. On a
 * computer with an NVIDIA graphics card, the first time an engine is chosen it is set up under the app's folder
 * (VoiceCloneSetup: a portable Python, the engine's packages and weights), then the helper
 * (tools/voice-clone/server.py, carried in the app) is started on a free port of this computer — and for GPT-SoVITS
 * its own server beside it — and stopped when another engine is chosen or the app quits.
 */
class VoiceCloneRuntime(
    private val root: File,
    private val os: VoiceCloneSetup.Os = if (System.getProperty("os.name").startsWith("Windows")) {
        VoiceCloneSetup.Os.WINDOWS
    } else {
        VoiceCloneSetup.Os.LINUX
    },
    private val hasGpu: () -> Boolean = ::nvidiaCard,
    // How long a server may take to answer: loading a network onto the graphics card takes a while.
    private val startupMs: Long = 180_000L,
) {
    private var running: VoiceClones.Engine? = null
    private var address: String? = null
    private val processes = ArrayList<Process>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    /**
     * The helper for [engine], set up and started where it is not yet — telling [say] what it is doing — its address,
     * or null where it cannot run here (no NVIDIA graphics card, a step that failed), [say] told why. Blocks; the first
     * time for minutes.
     */
    @Synchronized
    fun helper(engine: VoiceClones.Engine, say: (String) -> Unit): String? {
        address?.takeIf { running == engine && processes.all(Process::isAlive) }?.let { return it }
        stop()
        if (!hasGpu()) {
            say("The original voices need an NVIDIA graphics card; this computer has none.")
            return null
        }
        val python = VoiceCloneSetup.prepare(engine, root, os, Hands(), say) ?: run {
            say("The original voices could not be set up; the usual voices speak. It is tried again next time.")
            return null
        }
        say("Starting the original voices…")
        return launch(engine, python) ?: failed(say)
    }

    /**
     * The helper for [engine] started with the interpreter [python] (and for GPT-SoVITS its server beside it): its
     * address once it answers, null where it does not.
     */
    @Synchronized
    internal fun launch(engine: VoiceClones.Engine, python: File): String? {
        val sovits = if (engine == VoiceClones.Engine.GPT_SOVITS) {
            val port = freePort()
            val dir = File(root, "${engine.id}/${VoiceCloneSetup.SOVITS_SOURCE}")
            start(listOf(python.path, "api_v2.py", "-a", "127.0.0.1", "-p", "$port", "-c",
                "GPT_SoVITS/configs/tts_infer.yaml"), dir)
            "http://127.0.0.1:$port".also { if (!answers("$it/docs", startupMs)) return null }
        } else {
            null
        }
        val port = freePort()
        val command = listOf(python.path, serverScript().path, "--port", "$port",
            "--checkpoints", File(root, "openvoice/checkpoints_v2").path) +
            (sovits?.let { listOf("--gpt-sovits", it) } ?: emptyList())
        // XTTS-v2's licence, the Coqui Public Model License, was accepted by the household in choosing it.
        val env = if (engine == VoiceClones.Engine.XTTS) mapOf("COQUI_TOS_AGREED" to "1") else emptyMap()
        start(command, File(root, engine.id), env)
        val at = "http://127.0.0.1:$port"
        if (!answers("$at/health", startupMs)) return null
        running = engine
        address = at
        return at
    }

    private fun failed(say: (String) -> Unit): String? {
        stop()
        say("The original voices did not start; the usual voices speak.")
        return null
    }

    /** Stops the helper and whatever it runs beside it. */
    @Synchronized
    fun stop() {
        processes.forEach { p ->
            p.descendants().forEach { it.destroy() }
            p.destroy()
            if (!p.waitFor(STOP_S, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        processes.clear()
        running = null
        address = null
    }

    private fun start(command: List<String>, dir: File, env: Map<String, String> = emptyMap()): Process {
        dir.mkdirs()
        val log = File(root, "helper.log")
        return ProcessBuilder(command).directory(dir).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .also { it.environment().putAll(env) }
            .start().also { processes += it }
    }

    // The helper carried in the app, written beside the engines (the interpreters run files, not resources).
    private fun serverScript(): File = File(root, "server.py").also { file ->
        val carried = requireNotNull(javaClass.getResourceAsStream("/voice-clone/server.py")) {
            "server.py is not carried"
        }.use { it.readBytes() }
        if (!file.isFile || !file.readBytes().contentEquals(carried)) file.writeBytes(carried)
    }

    /** The platform's part of a setup: fetching through ModelFolder, unpacking, running to the end. */
    private inner class Hands : VoiceCloneSetup.Hands {
        override fun fetch(download: NeuralModels.Download, dir: File, progress: (Float) -> Unit): Boolean =
            ModelFolder.fetch(dir, download, progress)

        override fun unpack(file: File, into: File): Boolean =
            file.inputStream().use { if (file.name.endsWith(".zip")) unzip(it, into) else TarGz.extract(it, into) }

        override fun run(command: List<String>, dir: File): String? = runCatching {
            dir.mkdirs()
            val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            File(root, "setup.log").appendText("$ ${command.joinToString(" ")}\n$output\n")
            output.takeIf { process.waitFor() == 0 }
        }.getOrNull()
    }

    companion object {
        private const val STOP_S = 5L

        /** Whether this computer has an NVIDIA graphics card with its driver: `nvidia-smi -L` names one. */
        fun nvidiaCard(): Boolean = runCatching {
            val process = ProcessBuilder("nvidia-smi", "-L").redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 && out.contains("GPU")
        }.getOrDefault(false)

        /** A port free on this computer right now. */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /** Whether [url] answers 200 within [timeoutMs], asked every half second. */
        fun answers(url: String, timeoutMs: Long): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val ok = runCatching {
                    val c = URI(url).toURL().openConnection() as HttpURLConnection
                    c.connectTimeout = 1000
                    c.readTimeout = 2000
                    try {
                        c.responseCode == 200
                    } finally {
                        c.disconnect()
                    }
                }.getOrDefault(false)
                if (ok) return true
                Thread.sleep(500)
            }
            return false
        }

        /** A zip unpacked into [dir]; false where an entry reaches outside it. */
        fun unzip(input: java.io.InputStream, dir: File): Boolean = runCatching {
            val root = dir.canonicalFile.also { it.mkdirs() }
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(root, entry.name).canonicalFile
                    check(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
                        "${entry.name} lies outside the folder"
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            true
        }.getOrDefault(false)
    }
}

/** The app's one helper runtime, under its folder: one helper at a time, whichever player asks. */
object DesktopVoiceClones {
    val runtime: VoiceCloneRuntime by lazy { VoiceCloneRuntime(File(AppFolder.current(), "voice-clone")) }
}
