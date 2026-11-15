package com.hawkynt.mediadroyd.desktop

import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import javax.swing.SwingUtilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class X11PingTest {
    @Test
    fun `only a WM_PROTOCOLS message naming _NET_WM_PING is a ping`() {
        assertTrue(X11Ping.isPing(messageType = 7, first = 9, wmProtocols = 7, netWmPing = 9))
        assertFalse(X11Ping.isPing(messageType = 7, first = 8, wmProtocols = 7, netWmPing = 9))
        assertFalse(X11Ping.isPing(messageType = 6, first = 9, wmProtocols = 7, netWmPing = 9))
    }

    @Test
    fun `a window on X11 offers _NET_WM_PING once installed, beside what AWT offers`() {
        assumeTrue("needs an X11 display", !GraphicsEnvironment.isHeadless())
        assumeTrue("needs AWT on X11", Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit")
        lateinit var frame: Frame
        SwingUtilities.invokeAndWait {
            frame = Frame("X11PingTest").apply {
                setSize(200, 100)
                isVisible = true
            }
        }
        try {
            var installed = false
            var offered = false
            SwingUtilities.invokeAndWait {
                assertFalse(X11Ping.offers(frame))
                installed = X11Ping.install(frame)
                offered = X11Ping.offers(frame)
            }
            assertTrue("not installed: are java.desktop's packages opened to the app?", installed)
            assertTrue(offered)
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}
