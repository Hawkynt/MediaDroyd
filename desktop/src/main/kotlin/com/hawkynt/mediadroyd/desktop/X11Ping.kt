package com.hawkynt.mediadroyd.desktop

import java.awt.Component
import java.awt.Toolkit
import java.awt.Window
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * The window answers the window manager's "are you still there?" on X11 (#502): EWMH's `_NET_WM_PING`, which a window
 * offers in its `WM_PROTOCOLS` and answers by sending the ping back to the root window. Java's AWT does neither, so a
 * window manager that pings anyway — TileWin, on wlroots, whenever a window is clicked, typed into or focused — took
 * the idle, working window for hung after five seconds and greyed it out as "(Not Responding)".
 *
 * AWT has no API for it: its X11 toolkit is reached by reflection (the desktop app opens `java.desktop`'s `sun.awt`,
 * `sun.awt.X11` and `java.awt` to itself, desktop/build.gradle.kts). Wherever that is not there — Windows, a headless
 * or Wayland-native toolkit, another Java — nothing is done.
 */
object X11Ping {
    // X11's ClientMessage event, and the event masks a reply to the root window goes out with (EWMH).
    private const val CLIENT_MESSAGE = 33
    private const val SUBSTRUCTURE_NOTIFY = 1L shl 19
    private const val SUBSTRUCTURE_REDIRECT = 1L shl 20

    /** How many pings were answered, for the tests. */
    val answered = AtomicInteger()

    /**
     * Whether a ClientMessage of [messageType] whose first datum is [first] is a ping: a `WM_PROTOCOLS` message
     * ([wmProtocols]) naming `_NET_WM_PING` ([netWmPing]).
     */
    fun isPing(messageType: Long, first: Long, wmProtocols: Long, netWmPing: Long): Boolean =
        messageType == wmProtocols && first == netWmPing

    /** Makes [window] offer and answer `_NET_WM_PING` on X11, once it is shown; whether it could. */
    fun install(window: Window): Boolean = runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return false
        val x11 = X11(toolkit.javaClass)
        val peer = Component::class.java.getDeclaredField("peer").apply { isAccessible = true }.get(window)
            ?: return false
        val xid = x11.baseWindow.getMethod("getWindow").invoke(peer) as Long
        val protocols = x11.atom("WM_PROTOCOLS")
        val ping = x11.atom("_NET_WM_PING")
        x11.locked { x11.offer(peer, protocols, ping) }
        val dispatcher = Proxy.newProxyInstance(x11.loader, arrayOf(x11.dispatcherType)) { _, method, args ->
            if (method.name == "dispatchEvent") x11.answer(args[0], protocols, ping)
            null
        }
        x11.addDispatcher.invoke(null, xid, dispatcher)
        true
    }.getOrDefault(false)

    /** Whether [window] offers `_NET_WM_PING` in its WM_PROTOCOLS; false off X11. */
    fun offers(window: Window): Boolean = runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return false
        val x11 = X11(toolkit.javaClass)
        val peer = Component::class.java.getDeclaredField("peer").apply { isAccessible = true }.get(window)
            ?: return false
        x11.locked { x11.offered(peer, x11.atom("WM_PROTOCOLS"), x11.atom("_NET_WM_PING")) }
    }.getOrDefault(false)

    // AWT's X11 toolkit, reached by reflection.
    private class X11(toolkit: Class<*>) {
        val loader: ClassLoader? = toolkit.classLoader
        private fun type(name: String) = Class.forName("sun.awt.X11.$name", false, loader)
        private fun Class<*>.find(name: String, vararg params: Class<*>): Method =
            getDeclaredMethod(name, *params).apply { isAccessible = true }

        val baseWindow = type("XBaseWindow")
        val dispatcherType = type("XEventDispatcher")
        val addDispatcher = toolkit.find("addEventDispatcher", Long::class.java, dispatcherType)
        private val display = toolkit.find("getDisplay")
        private val root = toolkit.find("getDefaultRootWindow")
        private val sun = Class.forName("sun.awt.SunToolkit", false, loader)
        private val lock = sun.find("awtLock")
        private val unlock = sun.find("awtUnlock")
        private val atomType = type("XAtom")
        private val atomGet = atomType.find("get", String::class.java)
        private val atomValue = atomType.find("getAtom")
        private val listType = type("XAtomList")
        private val listOf = atomType.find("getAtomListPropertyList", baseWindow)
        private val listAdd = listType.find("add", atomType)
        private val listContains = listType.find("contains", atomType)
        private val listSet = atomType.find("setAtomListProperty", baseWindow, listType)
        private val event = type("XEvent")
        private val eventType = event.find("get_type")
        private val eventClient = event.find("get_xclient")
        private val eventData = event.find("getPData")
        private val client = type("XClientMessageEvent")
        private val clientType = client.find("get_message_type")
        private val clientData = client.find("get_data", Int::class.java)
        private val clientWindow = client.find("set_window", Long::class.java)
        private val long = Long::class.java
        private val sendEvent = type("XlibWrapper").find("XSendEvent", long, long, Boolean::class.java, long, long)

        fun atom(name: String): Any = atomGet.invoke(null, name)

        fun <T> locked(block: () -> T): T {
            lock.invoke(null)
            try {
                return block()
            } finally {
                unlock.invoke(null)
            }
        }

        // [ping] added to the window's WM_PROTOCOLS ([protocols]) where it is not there yet.
        fun offer(peer: Any, protocols: Any, ping: Any) {
            val list = listOf.invoke(protocols, peer)
            if (listContains.invoke(list, ping) == true) return
            listAdd.invoke(list, ping)
            listSet.invoke(protocols, peer, list)
        }

        fun offered(peer: Any, protocols: Any, ping: Any): Boolean =
            listContains.invoke(listOf.invoke(protocols, peer), ping) == true

        // A ping in [xEvent] sent back to the root window, as EWMH asks.
        fun answer(xEvent: Any, protocols: Any, ping: Any) {
            if (eventType.invoke(xEvent) != CLIENT_MESSAGE) return
            val message = eventClient.invoke(xEvent)
            val pinged = isPing(
                clientType.invoke(message) as Long,
                clientData.invoke(message, 0) as Long,
                atomValue.invoke(protocols) as Long,
                atomValue.invoke(ping) as Long,
            )
            if (!pinged) return
            val rootWindow = root.invoke(null) as Long
            clientWindow.invoke(message, rootWindow)
            locked {
                sendEvent.invoke(
                    null,
                    display.invoke(null),
                    rootWindow,
                    false,
                    SUBSTRUCTURE_NOTIFY or SUBSTRUCTURE_REDIRECT,
                    eventData.invoke(xEvent),
                )
            }
            answered.incrementAndGet()
        }
    }
}
