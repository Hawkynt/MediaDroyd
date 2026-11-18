package com.hawkynt.mediadroyd.data

import android.content.Context
import com.hawkynt.mediadroyd.BuildConfig
import com.hawkynt.mediadroyd.player.NativeEspeak
import com.hawkynt.mediadroyd.player.PiperSpeaker
import com.hawkynt.mediadroyd.player.PiperVoice
import com.hawkynt.mediadroyd.player.PiperVoices
import java.io.File

/**
 * The spoken subtitles' voices on Android (#531): Piper's networks on ONNX Runtime's Android library, fetched into the
 * app's models (ModelStore); espeak-ng's library and data only in builds for the household's own phones (-Pespeak):
 * the library `mediadroyd_espeak`, its data as the assets' `espeak-ng-data`, unpacked once per version into the app's
 * own files, where espeak-ng reads it by path.
 */
object AndroidVoices {
    private const val DATA = "espeak-ng-data"

    /** Whether this build carries espeak-ng: its library loads and its data is among the assets. Cheap after once. */
    val espeak: Boolean by lazy {
        runCatching { System.loadLibrary("mediadroyd_espeak") }.isSuccess
    }

    private fun hasData(context: Context): Boolean =
        runCatching { context.assets.list(DATA)?.isNotEmpty() == true }.getOrDefault(false)

    /** The folder espeak-ng's data is unpacked into (it holds `espeak-ng-data`), unpacked now where it is not yet. */
    @Synchronized
    fun dataDir(context: Context): File? {
        if (!espeak || !hasData(context)) return null
        val root = File(context.filesDir, "espeak")
        val stamp = File(root, "version")
        val version = BuildConfig.VERSION_CODE.toString()
        if (stamp.isFile && stamp.readText() == version && File(root, DATA).isDirectory) return root
        root.deleteRecursively()
        if (!copy(context, DATA, File(root, DATA))) return null
        stamp.writeText(version)
        return root
    }

    // The asset [path] (a folder, its files and folders below) copied to [into].
    private fun copy(context: Context, path: String, into: File): Boolean = runCatching {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            into.parentFile?.mkdirs()
            context.assets.open(path).use { input -> into.outputStream().use { input.copyTo(it) } }
        } else {
            into.mkdirs()
            for (child in children) check(copy(context, "$path/$child", File(into, child)))
        }
        true
    }.getOrDefault(false)

    /** The voices a subtitle track in [language] can be spoken in here. */
    fun offered(language: String?): List<PiperVoices.Entry> = PiperVoices.forLanguage(language, espeak)

    /** [entry] speaking, its files fetched; null where it cannot here. Blocks: unpacks espeak-ng's data once. */
    fun speaker(context: Context, entry: PiperVoices.Entry): PiperSpeaker? = runCatching {
        val data = dataDir(context)
        PiperSpeaker(ModelStore.folder(context, entry.download), entry) { text, voice ->
            if (data == null || voice.phonemes != PiperVoice.Phonemes.ESPEAK) {
                null
            } else {
                NativeEspeak.phonemize(data.path, voice.espeakVoice ?: return@PiperSpeaker null, text)
            }
        }
    }.getOrNull()
}
