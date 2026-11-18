package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.NativeEspeak
import com.hawkynt.mediadroyd.player.PiperSpeaker
import com.hawkynt.mediadroyd.player.PiperVoice
import com.hawkynt.mediadroyd.player.PiperVoices
import java.io.File

/**
 * espeak-ng on the desktop (#531): `mediadroyd_espeak` and its data, beside the native library — only in builds for
 * one's own machines (-Pespeak). Loaded once; [dataDir] null where it is not there.
 */
object EspeakLibrary {
    /** The folder holding `espeak-ng-data` once the library loaded; null where it is not carried. */
    val dataDir: File? by lazy {
        val folder = System.getProperty(NativeNetworks.FOLDER_PROPERTY)?.let(::File) ?: NativeNetworks.file?.parentFile
            ?: return@lazy null
        val library = File(folder, System.mapLibraryName("mediadroyd_espeak"))
        if (!library.isFile || !File(folder, "espeak-ng-data").isDirectory) return@lazy null
        runCatching { System.load(library.absolutePath) }.getOrNull() ?: return@lazy null
        folder
    }

    /** espeak-ng's phonemes for a Piper voice's [text]; null where it is not carried or does not know the voice. */
    fun phonemize(text: String, voice: PiperVoice): String? {
        val data = dataDir ?: return null
        if (voice.phonemes != PiperVoice.Phonemes.ESPEAK) return null
        return NativeEspeak.phonemize(data.path, voice.espeakVoice ?: return null, text)
    }

    /** The voice [entry] (its files in [dir]) speaking on the desktop. */
    fun speaker(dir: File, entry: PiperVoices.Entry): PiperSpeaker = PiperSpeaker(dir, entry, ::phonemize)
}
