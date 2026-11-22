package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.player.CubeLuts
import com.hawkynt.mediadroyd.player.MpvShaders
import java.io.File

/**
 * The folder of mpv user shaders (.glsl) the picture offers (MpvShaders), and of `.cube` tables for looks and screens'
 * calibrations (CubeLuts): the one picked in Settings while it is there, else `shaders/` in the app-data folder, made
 * when first asked for.
 */
object ShaderFolder {
    /** The folder the shaders are read from: [picked] (the household's setting) where it is a folder, else [app]'s. */
    fun of(picked: String, app: File): File =
        picked.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
            ?: File(app, "shaders").apply { mkdirs() }

    /**
     * Reads every shader and table in [folder] into MpvShaders and CubeLuts, by its name; returns how many shaders it
     * offers then.
     */
    fun load(folder: File): Int {
        val files = folder.listFiles().orEmpty()
            .filter { it.isFile && (MpvShaders.isShader(it.name) || CubeLuts.isLut(it.name)) }
            .associate { it.name to it.readText() }
        MpvShaders.load(files)
        CubeLuts.load(files)
        return MpvShaders.choices.size
    }
}
