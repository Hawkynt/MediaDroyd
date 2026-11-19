package com.hawkynt.mediadroyd.player

/**
 * whisper.cpp's Whisper (MIT; app/THIRD-PARTY-NOTICE.whisper.cpp.txt) as the native library carries it
 * (native/jni/speech.cpp), for subtitles made from the speech (#529). The library is the app's own, loaded by the
 * platform first. A context ([open]) belongs to one caller at a time.
 */
object NativeWhisper {
    /** The rate Whisper hears at. */
    const val SAMPLE_RATE = 16_000

    /** The model in the ggml file [model] loaded; 0 where it does not load. */
    @JvmStatic
    external fun open(model: String): Long

    @JvmStatic
    external fun close(handle: Long)

    /**
     * [samples] (mono floats at [SAMPLE_RATE], at most 30 s heard at once by Whisper, longer cut into windows) as text:
     * the language first, then a line per segment — start and end in ms from the samples' start, its text — tab
     * separated; with [translate] the text in English. Null where Whisper failed. Blocks.
     */
    @JvmStatic
    external fun transcribe(handle: Long, samples: FloatArray, language: String, translate: Boolean, threads: Int): String?
}
