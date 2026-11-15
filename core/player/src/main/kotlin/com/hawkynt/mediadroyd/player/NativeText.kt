package com.hawkynt.mediadroyd.player

/**
 * The native part's text reader (native/src/reader.cpp: PaddleOCR's PP-OCRv5 on ncnn, NeuralModels.PPOCR) for credits
 * recognised by their text (CreditText, #493), inside the library NativeInterpolation loads: the Android app's
 * `libmediadroyd_neural.so`, the desktop's `libmediadroyd.so` / `mediadroyd.dll`.
 */
object NativeText {
    /** The reader from the folder [dir] (in [assets], an Android AssetManager, where given), on the GPU or the CPU. */
    external fun open(assets: Any?, dir: String, gpu: Boolean): Long

    /**
     * The lines in [grey] ([width] x [height], a byte a pixel), flattened: for each line its x, y, width, height, the
     * number of its ids and the ids (CreditText.lines reads them); null where the handle is 0.
     */
    external fun read(handle: Long, grey: ByteArray, width: Int, height: Int): IntArray?

    /** Frees the reader [open] gave. */
    external fun close(handle: Long)
}
