package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vendored NNEDI3 prescaler, read as the mpv user shaders are. */
class PrescalersTest {

    @Test
    fun `NNEDI3 doubles the brightness down and across in four passes the stages run`() {
        val shader = Prescalers.nnedi3
        assertNull(shader.unsupported)
        assertEquals(4, shader.passes.size)
        assertTrue(shader.passes.all { "LUMA" in it.hooks })
        assertTrue(shader.description.startsWith("NNEDI3"))
    }

    @Test
    fun `its rows alone are the first two passes, the height doubled`() {
        val rows = Prescalers.rows
        assertNull(rows.unsupported)
        assertEquals(2, rows.passes.size)
        assertTrue(rows.passes.last().height != null)
    }
}
