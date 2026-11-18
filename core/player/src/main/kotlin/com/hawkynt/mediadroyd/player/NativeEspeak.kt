package com.hawkynt.mediadroyd.player

/**
 * espeak-ng's phonemes for Piper's voices (#531; GPL-3.0-or-later, app/THIRD-PARTY-NOTICE.espeak-ng.txt), from the
 * library of its own builds for the household's devices carry (native/espeak, -Pespeak) — never a release. The platform
 * loads `mediadroyd_espeak` where it is there; [phonemize] tells whether it was.
 */
object NativeEspeak {
    /**
     * The IPA phonemes of [text] in the espeak-ng voice [voice] (Piper's config names it), with espeak-ng's data in
     * [dataDir] (the folder that holds `espeak-ng-data`): clause by clause (PiperVoice.clauses), each with its
     * punctuation, joined as Piper joins them (PiperVoice.join); null where the library is not loaded or the voice not
     * known. One caller at a time: espeak-ng keeps its voice for the process.
     */
    @Synchronized
    fun phonemize(dataDir: String, voice: String, text: String): String? = runCatching {
        val clauses = PiperVoice.clauses(text).map { (words, end) ->
            (phonemes(dataDir, voice, words) ?: return@runCatching null) to end
        }
        PiperVoice.join(clauses)
    }.getOrNull()

    /** espeak-ng's phonemes for [text], a clause at a time joined by spaces; null where it cannot make them. */
    @JvmStatic
    external fun phonemes(dataDir: String, voice: String, text: String): String?
}
