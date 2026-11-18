package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.FilmSound
import java.util.concurrent.ConcurrentHashMap

/**
 * The readers playback reads ahead with, by location (#535): the analysis taps the film playing from them
 * (SpeechReads) instead of reading its file again.
 */
object SharedReads {
    private val readers = ConcurrentHashMap<String, MediaReader>()

    /** [reader] reads [location] ahead for everyone from now on. */
    fun put(location: String, reader: MediaReader) {
        readers[location] = reader
    }

    /** [reader] no longer reads [location]. */
    fun remove(location: String, reader: MediaReader) {
        readers.remove(location, reader)
    }

    /** [location]'s sound from [fromMs] tapped from its reader ahead; null where none reads it near there. */
    fun tap(location: String, fromMs: Long, fallback: (atMs: Long) -> FilmSound.Stream?): FilmSound.Stream? =
        readers[location]?.soundTap(fromMs, fallback)
}
