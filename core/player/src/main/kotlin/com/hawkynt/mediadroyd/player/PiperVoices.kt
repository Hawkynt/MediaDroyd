package com.hawkynt.mediadroyd.player

/**
 * The voices spoken subtitles can be spoken in (#531), fetched when chosen (ModelFolder.fetch, every file checked
 * against its digest): Piper's own voices from rhasspy/piper-voices at a fixed revision — each phonemised by espeak-ng,
 * so offered only where the build carries it ([PiperVoice.Phonemes.ESPEAK], builds for one's own devices). Only voices
 * whose data's licence allows any use are listed (CC0, CC BY 4.0), the licence named with each.
 */
object PiperVoices {
    /** rhasspy/piper-voices at the revision the digests below are of. */
    private const val SOURCE =
        "https://huggingface.co/rhasspy/piper-voices/resolve/0ca208afc2a2ee102b1e961e72e0a7e4907be3d0"

    /**
     * A voice to choose: its [id] (its files' name), what to call it ([label]), the [language] it speaks (BCP 47), how
     * many [speakers] it holds (a voice per character, #532, can pick among them), its data's [licence] and its files.
     */
    data class Entry(
        val id: String,
        val label: String,
        val language: String,
        val speakers: Int,
        val licence: String,
        val phonemes: PiperVoice.Phonemes,
        val download: NeuralModels.Download,
        /** Whose voice it is: a man's, a woman's, or many people's. */
        val sex: Sex = Sex.MANY,
    ) {
        /** The network's file and its config's. */
        val model: String get() = "$id.onnx"
        val config: String get() = "$id.onnx.json"
    }

    /** Whose voice a voice is. */
    enum class Sex { MALE, FEMALE, MANY }

    private fun piper(
        path: String,
        id: String,
        label: String,
        language: String,
        speakers: Int,
        licence: String,
        onnx: Pair<Long, String>,
        json: Pair<Long, String>,
        sex: Sex = Sex.MANY,
    ) = Entry(
        id, label, language, speakers, licence, PiperVoice.Phonemes.ESPEAK,
        NeuralModels.Download(
            "voice-$id",
            listOf(
                NeuralModels.File("$id.onnx", onnx.first, onnx.second),
                NeuralModels.File("$id.onnx.json", json.first, json.second),
            ),
            source = "$SOURCE/$path",
        ),
        sex,
    )

    val ALL: List<Entry> = listOf(
        piper(
            "de/de_DE/thorsten/medium", "de_DE-thorsten-medium", "Thorsten", "de-DE", 1, "CC0",
            63_221_984L to "fd270eb3d1ebf91d763f8a0f949b1b1bebdfefaea18c3aaf8577dd34536e064f",
            6_455L to "e5bb7410dcbbcc0be6f4544f6bc66612b315ab5cb3099ec44bb46d860bc7cca9",
            sex = Sex.MALE,
        ),
        piper(
            "de/de_DE/kerstin/low", "de_DE-kerstin-low", "Kerstin", "de-DE", 1, "CC0",
            63_104_526L to "d352a7641892cebf2903859af94e9ba81a141110215fe3943bcda7f7da401b7a",
            4_158L to "56e708556b7b9b7a53c4f8957e021421e69f11a600962bba554cffbe72cf2d47",
            sex = Sex.FEMALE,
        ),
        piper(
            "en/en_US/libritts_r/medium", "en_US-libritts_r-medium", "LibriTTS-R (904 voices)", "en-US", 904,
            "CC BY 4.0",
            78_580_914L to "10bb85e071d616fcf4071f369f1799d0491492ab3c5d552ec19fb548fac13195",
            20_123L to "b471dc60d2d8335e819c393d196d6fbf792817f40051257b269878505bc9afb3",
        ),
        piper(
            "en/en_US/joe/medium", "en_US-joe-medium", "Joe", "en-US", 1, "CC0",
            63_221_984L to "abf4b998e34045cbd00eaf050de2de620f81ded6eb8b85a24b022a1c2b3f08d2",
            6_284L to "9b91a652090ffa8a49e36569afa9acc8ead2b3a22a950e8e6ca11914f3e85d56",
            sex = Sex.MALE,
        ),
        piper(
            "en/en_GB/alba/medium", "en_GB-alba-medium", "Alba", "en-GB", 1, "CC BY 4.0",
            63_221_984L to "1f9da809b6da1ef4190bb5dd6cce4f4af803274539e9cf9e0693085150bac3d7",
            6_295L to "ed4b1f015a8aae5eb26d4bdeed7b712c660f5698a0dd954aa6df9c8be60b900b",
            sex = Sex.FEMALE,
        ),
        piper(
            "en/en_GB/cori/medium", "en_GB-cori-medium", "Cori", "en-GB", 1, "CC BY 4.0",
            63_221_984L to "2e0a55e140de672aa19b182602b407607bd2e6c35fc6020c0b1516053c02e995",
            6_354L to "7204f5ead17759278419129290e51ebb414f09f7ac6cd0e567532592956bbbac",
            sex = Sex.FEMALE,
        ),
        piper(
            "fr/fr_FR/siwis/medium", "fr_FR-siwis-medium", "Siwis", "fr-FR", 1, "CC BY 4.0",
            63_221_984L to "b1d946fd68d0347155fa59bce0a018924abbef3c1561181db45ca463d83963ef",
            6_369L to "3b3efdbcdcae930c40d8255527170ec5c87bf05e510b67f53d75fd028df40f43",
            sex = Sex.FEMALE,
        ),
        piper(
            "es/es_ES/davefx/medium", "es_ES-davefx-medium", "Davefx", "es-ES", 1, "CC0",
            63_221_984L to "d2c9224e122321aac3c33343957fff4d0f465226cb74c66b0f520ca3f8c0dd07",
            6_175L to "7d82d069eccca0f87963bd23c33166fe1ef6d24a6b231543931bfe692397f256",
            sex = Sex.MALE,
        ),
        piper(
            "it/it_IT/paola/medium", "it_IT-paola-medium", "Paola", "it-IT", 1, "CC0",
            63_221_984L to "f9b8658aa8c01f8280aa88488f6e7643a639b6225a81514c40440a01f6364a20",
            6_114L to "10e46e8534dd2d4051f1534ba6542f189f854454acea44a6d45ff464a83260fa",
            sex = Sex.FEMALE,
        ),
    )

    /** The voices offered for subtitles in [language] (ISO 639, as a track names it): its language's first. */
    fun forLanguage(language: String?, espeak: Boolean): List<Entry> {
        val usable = ALL.filter { espeak || it.phonemes != PiperVoice.Phonemes.ESPEAK }
        val code = language?.lowercase()?.let { com.hawkynt.mediadroyd.library.Languages.twoLetter(it) }
        return usable.sortedBy { if (code != null && it.language.startsWith(code)) 0 else 1 }
    }

    /** The id that chooses a man's or a woman's voice line by line, by the voice that spoke it (#532). */
    const val AUTO = "auto"

    /**
     * The man's and the woman's voice for subtitles in [language] — the first of each sex among the voices offered —
     * for choosing by the speaker; either null where the language has none.
     */
    fun pair(language: String?, espeak: Boolean): Pair<Entry?, Entry?> {
        val code = language?.lowercase()?.let { com.hawkynt.mediadroyd.library.Languages.twoLetter(it) }
        val own = forLanguage(language, espeak).filter { code != null && it.language.startsWith(code) }
        return own.firstOrNull { it.sex == Sex.MALE } to own.firstOrNull { it.sex == Sex.FEMALE }
    }

    /** The voice [id] names, or null. */
    fun of(id: String?): Entry? = ALL.firstOrNull { it.id == id }
}
