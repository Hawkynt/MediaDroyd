package com.hawkynt.mediadroyd.player

/**
 * The translators subtitles can be translated with (#530): OPUS-MT's Marian models (Helsinki-NLP, CC BY 4.0) from
 * English into another language, as Xenova's quantised ONNX exports at fixed revisions, fetched when chosen and checked
 * against their digests. Whisper turns any speech into English (#529), so English into the language wanted covers any
 * spoken language — but small Whisper models translating Chinese or Japanese mostly loop and guess, so those are
 * written as spoken and translated into English by their own translators ([FROM], #546): Chinese speech → English →
 * German.
 */
object TranslationModels {
    /** A translator from [source] into [language] (ISO 639-1), what to call it (the other language), its files. */
    data class Entry(
        val language: String,
        val label: String,
        val download: NeuralModels.Download,
        val source: String = "en",
    )

    /** The files every export has, by name in the download (the networks in its onnx/ folder). */
    const val ENCODER = "onnx/encoder_model_quantized.onnx"
    const val DECODER = "onnx/decoder_model_merged_quantized.onnx"
    const val TOKENIZER = "tokenizer.json"
    const val CONFIG = "config.json"

    private fun model(
        language: String,
        label: String,
        revision: String,
        vararg files: Pair<Long, String>,
        source: String = "en",
    ) = Entry(
        language, label,
        NeuralModels.Download(
            "translate-$source-$language",
            listOf(DECODER, ENCODER, TOKENIZER, "vocab.json", CONFIG).zip(files) { name, (bytes, sha) ->
                NeuralModels.File(name, bytes, sha)
            },
            source = "https://huggingface.co/Xenova/opus-mt-$source-$language/resolve/$revision",
        ),
        source,
    )

    val ALL = listOf(
        model(
            "de", "German", "1ca130c44c4c5441ef16d48aae521a424ab644f7",
            56_652_404L to "8b46a825964cdd182fe47cc780f0fb87d357eae1f47cf20aa63c1c09be5b510c",
            49_366_942L to "15834b45fabd2dfb8c6c029b3ca3e7289aeefd90ece798ce42bcf548d1bd3b8d",
            5_498_450L to "8e0fcf45621ea87fa680c7f9969c37a7f819c1f4c7658a2e6e0879b866a14b17",
            1_389_436L to "d5acea957b265a78554999144459c5e391e0df525864edc8287bc090290baa44",
            1_411L to "517955ebf66cd6523f9982851dd41771b74fb2d9c00b4933ba2922ac7a63336f",
        ),
        model(
            "fr", "French", "28726206f80896b90035bd99cccd5cc1e151f916",
            57_381_512L to "333b244bce16023df04541c8cf9fd60aec9b0569da393c4b831d561897b0bda8",
            50_090_398L to "0a81bdba62f53223740a8c6c6f58e716eba8ea1f92dfa569caf27ed5e3b6a0f7",
            5_637_839L to "8391785c1a2139e7af4678571ccd8dc654ecbb72e4be186940f65d7c604f0246",
            1_458_196L to "f2ba9c69ae20f96b8bd821239a9152be422394f980350b77907cffc183db5f2d",
            1_411L to "b522b73fcc86f77981349c1df2a2e041eed0dbcbea4acf2635019cf21d51ffc0",
        ),
        model(
            "es", "Spanish", "4b002a4c7edd54a7ced58877258b87f7efd3f892",
            60_212_804L to "58ee5ddd6e22d1693d8722b90c1486afb93700a4376cf28fd74175052ad530ae",
            52_899_742L to "03434edd1147e6c49e22066e9dc5888235e99cc35a6711d1c4ea5b06455d5da9",
            6_262_682L to "285eb29e7155ee48851a77960797813f86a125f70d2c1a124f613f1fbd2b19c3",
            1_720_044L to "b074b4cca0036ade5a39ea97faabd534e1015482c480fc2cb02c6481983eb163",
            1_468L to "499eae1f97eeba63172fc884f92fd727ae803b7ceb79c0f653aa4b011152af2f",
        ),
        model(
            "it", "Italian", "075406e3c8c2c30634d4a1bd8f00c21d9e162011",
            67_970_348L to "f9db146ed7665ea7d437dc4df564e3af5df923366de4d52aaf2c4b4e3e4ae4f1",
            60_597_150L to "b51c5110c7ba356ef01ee400d427afe162e657de1d7a2e9a40deedd811b49e6f",
            7_904_597L to "fb93411d5198eb85753bbd68e9ac24ccb42071003214851b2f37c81f2ea50416",
            2_511_647L to "40c98ffc0013f2f09ed9127418131738ad9c2de4d8f7f52d52532332bb2bf78c",
            1_376L to "1cf40ab3aa16e8b6ea7c9766981de1da705a161564c0d5ad187c1e62e5c8ee85",
        ),
        model(
            "nl", "Dutch", "c3a0541231a7329b4492809fc2bd7ab7d2ceb0bc",
            61_258_735L to "5f8034fddc4cdcb6fa6c3b64a06e1cd9b1955c0c6dcd13e0e47610ab99ac4a99",
            53_937_565L to "0febb990f8ca8ed9fd3e0017dfcb55ec5293999f20ead81d2bb4370e9e3120ff",
            6_433_167L to "c6b55d9ac70b2a566f0307c6f80d3136fb839ed8e70bb16186324747df58668b",
            1_794_274L to "b1fba91c620564d90848c5f77b03f13d17949790f8e7c7741e4a8ea13b8b0d91",
            1_376L to "3469caf791a6a038451e418d4254434087c94afc7d9676c03d38b9c64ce258c5",
        ),
        model(
            "ru", "Russian", "e050376e960175e8ddc5cff85025dc8436cddd68",
            58_931_576L to "7efefcd793a5663bc204224a92a123524f3dfe2ffad59e5f73d6734d1b11ad04",
            51_628_446L to "97f28d8295d231b7da721ded06fa8c034787df2122cd8f85b904abdc4d15bd53",
            7_205_388L to "981cd3d9fef6bb4dda8082afda716b65deb6717cb3ef35ac0b57002c09dec2bb",
            2_726_796L to "5cf0d95d930d8d3e783c9e2f46a72f08b43a18060dab4ddefbcb66a733efedcb",
            1_376L to "db16bf0ea7bac239273214c3b9ab44577884227371a9a25e0191b7baf8d6f167",
        ),
    )

    /** The translators into English from a language Whisper writes as spoken (#546). */
    val FROM = listOf(
        model(
            "en", "Chinese", "39d480d52a9ea3065a1f117adfe4dbc55de10e6f",
            60_212_804L to "c6b7f04ff1ba0fbd1bf6852599b4c0cad6fe512d57cd887f44ef36cf705424cb",
            52_899_742L to "84d5e171b626bc8b6b220d022ac58696e9528c25deeacca62b5cbf4364547a99",
            6_381_339L to "b306d0301cf280bfd647d7067b5ade2a97b987e6d678df110703c002433643ff",
            1_747_906L to "08a119a1defd522fa047cb5e3bfe3e89633e96caa38ced0dc9cee7ef1021a011",
            1_389L to "293d318fce41dbf04114eac45037bb88a32d7c4ee21011a75e24a8b98ca45ad1",
            source = "zh",
        ),
        model(
            "en", "Japanese", "1a906cfaaf7c8f4193f67f5885c082aa6dbd9d16",
            58_001_744L to "b304d0014e4e1575437b6af95467b6cb54405d923732d8359113bd6dbbee93c0",
            50_705_822L to "345262b16bcdda1468b0f3380c112b7ce79f731176b4b1d21f6edd5b2ae0d25c",
            5_991_485L to "770ff2855437cf44f1f110550c5a9dca773253a167aeac36076b2073d259aa3b",
            1_622_775L to "a1667b43d021e09c76f385101bd473f20b5691a12cdca7dda43e4a8daff20021",
            1_376L to "d35476f1d3858883feec67e5a63ddc387452a70fda9c8e26db99c71aa2ad6a34",
            source = "ja",
        ),
    )

    /** The translator from English into [language], or null where there is none. */
    fun into(language: String?): Entry? = ALL.firstOrNull { it.language == language }

    /** The translator from [language] into English, or null where Whisper's own English is the way (#546). */
    fun from(language: String?): Entry? = FROM.firstOrNull { it.source == language }
}
