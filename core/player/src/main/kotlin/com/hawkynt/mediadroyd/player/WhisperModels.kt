package com.hawkynt.mediadroyd.player

/**
 * The Whisper models subtitles can be made from the speech with (#529): OpenAI's multilingual Whisper (MIT) as
 * whisper.cpp's quantised ggml files, from ggerganov/whisper.cpp at a fixed revision, fetched when chosen and checked
 * against their digests. Larger hears better and takes longer: Tiny for slow phones, Base by default, Small for fast
 * phones and desktops, Turbo (large-v3-turbo) for desktops. Multilingual, so each also translates into English.
 */
object WhisperModels {
    private const val SOURCE =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1"

    /** A model to choose: its [label], its file, and whether it is meant for desktops (too slow for most phones). */
    data class Entry(
        val id: String,
        val label: String,
        val download: NeuralModels.Download,
        val desktop: Boolean = false,
    ) {
        val file: String get() = download.files.single().name
    }

    private fun model(id: String, label: String, bytes: Long, sha256: String, desktop: Boolean = false) = Entry(
        id, label,
        NeuralModels.Download(
            "whisper-$id", listOf(NeuralModels.File("ggml-$id.bin", bytes, sha256)), source = SOURCE,
        ),
        desktop,
    )

    val TINY = model("tiny-q5_1", "Tiny", 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7")
    val BASE = model("base-q5_1", "Base", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898")
    val SMALL = model(
        "small-q5_1", "Small", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        desktop = true,
    )
    val TURBO = model(
        "large-v3-turbo-q5_0", "Turbo", 574_041_195,
        "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        desktop = true,
    )

    val ALL = listOf(TINY, BASE, SMALL, TURBO)

    /** The model [id] names, else Base. */
    fun of(id: String?): Entry = ALL.firstOrNull { it.id == id } ?: BASE
}
