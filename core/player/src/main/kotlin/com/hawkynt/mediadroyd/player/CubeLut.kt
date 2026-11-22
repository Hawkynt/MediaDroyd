package com.hawkynt.mediadroyd.player

import kotlin.math.floor

/**
 * A colour lookup table from a `.cube` file (#448): a projector's or TV's calibration, or a look, as Adobe's "Cube LUT
 * Specification" 1.0 (2013) defines the format — the normative text this parser follows (AGENTS.md rung 3), its
 * behaviour checked against FFmpeg's lut3d filter (the oracle; `CubeLutTest`).
 *
 * The file is text: `#` starts a comment, keywords come before the table — `TITLE "…"`, `LUT_1D_SIZE N` (2 to 65536)
 * or `LUT_3D_SIZE N` (2 to 256), `DOMAIN_MIN r g b` and `DOMAIN_MAX r g b` (0 0 0 and 1 1 1 if absent; Resolve's
 * `LUT_1D_INPUT_RANGE` / `LUT_3D_INPUT_RANGE min max` read as the same) — then one line of three numbers per entry:
 * N for a 1D table, N³ for a 3D one, red changing fastest, then green, blue slowest. An input is scaled from the
 * domain to the table's indices, clamped there; a 3D table is read between its entries trilinearly or by tetrahedra
 * (the cube cell cut into six, four entries weighed — what calibration software expects and FFmpeg's default), a 1D
 * table linearly, each channel on its own.
 */
class CubeLut private constructor(
    /** The file's title, or "". */
    val title: String,
    /** Entries along each axis. */
    val size: Int,
    /** A 3D table (N³ entries) or a 1D one (N entries, each channel looked up on its own). */
    val threeD: Boolean,
    /** The input range, per channel. */
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    /** The entries' R, G, B, one after the other, in the file's order. */
    val table: FloatArray,
) {
    /** How a 3D table is read between its entries. */
    enum class Interpolation { TRILINEAR, TETRAHEDRAL }

    /**
     * [r], [g], [b] (the domain's values) through the table: a 3D table read by [interpolation], a 1D one linearly per
     * channel.
     */
    fun apply(r: Float, g: Float, b: Float, interpolation: Interpolation = Interpolation.TETRAHEDRAL): FloatArray {
        val s = FloatArray(3) { c ->
            val v = floatArrayOf(r, g, b)[c]
            val span = domainMax[c] - domainMin[c]
            ((v - domainMin[c]) / span).coerceIn(0f, 1f) * (size - 1)
        }
        if (!threeD) {
            return FloatArray(3) { c ->
                val i = floor(s[c]).toInt().coerceAtMost(size - 2)
                val d = s[c] - i
                table[i * 3 + c] * (1f - d) + table[(i + 1) * 3 + c] * d
            }
        }
        val i = IntArray(3) { floor(s[it]).toInt().coerceAtMost(size - 2) }
        val d = FloatArray(3) { s[it] - i[it] }
        fun at(dr: Int, dg: Int, db: Int, c: Int) =
            table[(((i[2] + db) * size + i[1] + dg) * size + i[0] + dr) * 3 + c]
        val (fr, fg, fb) = Triple(d[0], d[1], d[2])
        return FloatArray(3) { c ->
            when (interpolation) {
                Interpolation.TRILINEAR -> {
                    fun lerp(a: Float, z: Float, t: Float) = a + (z - a) * t
                    val c00 = lerp(at(0, 0, 0, c), at(1, 0, 0, c), fr)
                    val c10 = lerp(at(0, 1, 0, c), at(1, 1, 0, c), fr)
                    val c01 = lerp(at(0, 0, 1, c), at(1, 0, 1, c), fr)
                    val c11 = lerp(at(0, 1, 1, c), at(1, 1, 1, c), fr)
                    lerp(lerp(c00, c10, fg), lerp(c01, c11, fg), fb)
                }
                Interpolation.TETRAHEDRAL -> {
                    val c000 = at(0, 0, 0, c)
                    val c111 = at(1, 1, 1, c)
                    when {
                        fr > fg && fg > fb ->
                            (1 - fr) * c000 + (fr - fg) * at(1, 0, 0, c) + (fg - fb) * at(1, 1, 0, c) + fb * c111
                        fr > fg && fr > fb ->
                            (1 - fr) * c000 + (fr - fb) * at(1, 0, 0, c) + (fb - fg) * at(1, 0, 1, c) + fg * c111
                        fr > fg ->
                            (1 - fb) * c000 + (fb - fr) * at(0, 0, 1, c) + (fr - fg) * at(1, 0, 1, c) + fg * c111
                        fb > fg ->
                            (1 - fb) * c000 + (fb - fg) * at(0, 0, 1, c) + (fg - fr) * at(0, 1, 1, c) + fr * c111
                        fb > fr ->
                            (1 - fg) * c000 + (fg - fb) * at(0, 1, 0, c) + (fb - fr) * at(0, 1, 1, c) + fr * c111
                        else ->
                            (1 - fg) * c000 + (fg - fr) * at(0, 1, 0, c) + (fr - fb) * at(1, 1, 0, c) + fb * c111
                    }
                }
            }
        }
    }

    /**
     * The table as the GL pass reads it (glsl/video/lut-es3.frag): RGBA floats, rows one after the other. A 3D table
     * as its blue slices of [size] × [size] (red across, green up) laid out [tilesAcross] to a row, so even 256 entries
     * fit a 4096 texture; a 1D table as one row of [size].
     */
    fun texture(): Texture {
        if (!threeD) {
            val out = FloatArray(size * 4) { i -> if (i % 4 == 3) 1f else table[i / 4 * 3 + i % 4] }
            return Texture(size, 1, out)
        }
        val width = size * tilesAcross
        val height = size * ((size + tilesAcross - 1) / tilesAcross)
        val out = FloatArray(width * height * 4)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val x = b % tilesAcross * size + r
            val y = b / tilesAcross * size + g
            val from = ((b * size + g) * size + r) * 3
            val to = (y * width + x) * 4
            out[to] = table[from]
            out[to + 1] = table[from + 1]
            out[to + 2] = table[from + 2]
            out[to + 3] = 1f
        }
        return Texture(width, height, out)
    }

    /** A 3D table's blue slices in a row of its texture. */
    val tilesAcross: Int get() = if (threeD) generateSequence(1) { it + 1 }.first { it * it >= size } else 1

    /** The table laid out for the GL pass: [width] × [height] RGBA floats, row by row. */
    class Texture(val width: Int, val height: Int, val rgba: FloatArray)

    companion object {
        /** The largest tables the specification allows. */
        const val MAX_1D = 65536
        const val MAX_3D = 256

        /** The `.cube` [text] read, titled [name] where it gives no title; null where it is no valid table. */
        fun parse(text: String, name: String = ""): CubeLut? = runCatching { read(text, name) }.getOrNull()

        private fun read(text: String, name: String): CubeLut? {
            var title = name
            var size1 = 0
            var size3 = 0
            val min = floatArrayOf(0f, 0f, 0f)
            val max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val words = line.split(Regex("\\s+"))
                when (words[0]) {
                    "TITLE" -> title = line.removePrefix("TITLE").trim().trim('"')
                    "LUT_1D_SIZE" -> size1 = words[1].toInt()
                    "LUT_3D_SIZE" -> size3 = words[1].toInt()
                    "DOMAIN_MIN" -> for (c in 0..2) min[c] = words[c + 1].toFloat()
                    "DOMAIN_MAX" -> for (c in 0..2) max[c] = words[c + 1].toFloat()
                    "LUT_1D_INPUT_RANGE", "LUT_3D_INPUT_RANGE" -> {
                        min.fill(words[1].toFloat())
                        max.fill(words[2].toFloat())
                    }
                    else -> {
                        // A keyword this version does not know is skipped, as the specification lets readers do.
                        val numbers = words.map { it.toFloatOrNull() }
                        if (numbers.size != 3 || numbers.any { it == null }) {
                            if (words[0].first().isLetter()) continue
                            return null
                        }
                        numbers.forEach { values += it!! }
                    }
                }
            }
            val threeD = size3 > 0
            if (threeD == (size1 > 0)) return null
            val size = if (threeD) size3 else size1
            if (size < 2 || size > (if (threeD) MAX_3D else MAX_1D)) return null
            val entries = if (threeD) size * size * size else size
            if (values.size != entries * 3) return null
            if ((0..2).any { max[it] <= min[it] }) return null
            return CubeLut(title, size, threeD, min, max, values.toFloatArray())
        }
    }
}

/**
 * The `.cube` tables the household put in its shader folder (MpvShaders' folder), by file name — what
 * [PictureAdjustment.lut] (a look, per video or folder) and [ScreenSetup.lut] (a screen's calibration) name. Set by
 * the app when it reads the folder, read by the stages on their own threads.
 */
object CubeLuts {
    @Volatile
    var library: Map<String, CubeLut> = emptyMap()
        private set

    /** [files] (file name to text) taken as the library: the `.cube` files that read as tables, by name. */
    fun load(files: Map<String, String>) {
        library = files.filterKeys(::isLut)
            .mapNotNull { (name, text) -> CubeLut.parse(text, label(name))?.let { name to it } }
            .toMap(sortedMapOf(String.CASE_INSENSITIVE_ORDER))
    }

    /** Whether the file [name] is a lookup table: `.cube`. */
    fun isLut(name: String): Boolean = name.endsWith(EXTENSION, ignoreCase = true)

    /** The file [name] as a choice is labelled: without its extension. */
    fun label(name: String): String = if (isLut(name)) name.dropLast(EXTENSION.length) else name

    /** The names a picture or a screen may choose. */
    val choices: List<String> get() = library.keys.toList()

    /**
     * The tables the stage applies, in order: [picture]'s look, then the calibration of the screen [warp] is for —
     * each where the library holds it.
     */
    fun chosen(picture: PictureAdjustment, warp: ScreenWarp): List<CubeLut> =
        listOfNotNull(picture.lut?.let(library::get), warp.calibration?.let(library::get))

    private const val EXTENSION = ".cube"
}
