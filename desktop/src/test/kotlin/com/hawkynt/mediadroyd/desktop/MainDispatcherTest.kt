package com.hawkynt.mediadroyd.desktop

import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop has a main dispatcher (#506): what asks for Dispatchers.Main — a dialog's lifecycle in Compose, among
 * others — runs on Swing's window thread instead of failing with "Module with the Main dispatcher is missing".
 */
class MainDispatcherTest {
    @Test
    fun `Dispatchers Main runs on the window thread`() {
        val onWindowThread = runBlocking { withContext(Dispatchers.Main) { SwingUtilities.isEventDispatchThread() } }
        assertTrue(onWindowThread)
    }
}
