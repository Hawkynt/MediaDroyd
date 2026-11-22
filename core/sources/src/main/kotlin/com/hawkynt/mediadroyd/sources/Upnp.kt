package com.hawkynt.mediadroyd.sources

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Finding UPnP media servers (#454) — a NAS, a router, a TV recorder running DLNA — as the UPnP Device Architecture
 * 1.1 specifies discovery (§1.3, SSDP): an M-SEARCH sent to the multicast group, each server answering to the sender
 * alone with the address of its description (LOCATION). The answers are unicast, so no multicast lock is needed to
 * hear them.
 */
object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900

    /** The search targets asked for: media servers, and their content directories (some answer to one only). */
    val TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaServer:1",
        "urn:schemas-upnp-org:service:ContentDirectory:1",
    )

    /** An answer to a search: where the server's description is, and its unique service name. */
    data class Answer(val location: String, val usn: String)

    /** The M-SEARCH request for [target], answers spread over [mx] seconds (§1.3.2). */
    fun search(target: String, mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $ADDRESS:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $target\r\n\r\n"

    /** A search's answer read from its [datagram]: an HTTP 200 with a LOCATION; null for anything else. */
    fun answer(datagram: String): Answer? {
        val lines = datagram.split("\r\n", "\n")
        if (!lines.first().trim().startsWith("HTTP/1.1 200")) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, colon).trim().uppercase() to line.substring(colon + 1).trim()
        }.toMap()
        val location = headers["LOCATION"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        return Answer(location, headers["USN"].orEmpty())
    }

    /** Searches for [timeoutMs] and returns the answers, one per description, in the order they came. */
    fun discover(timeoutMs: Int = 3000): List<Answer> = DatagramSocket().use { socket ->
        val group = InetAddress.getByName(ADDRESS)
        for (target in TARGETS) {
            val bytes = search(target).toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(bytes, bytes.size, group, PORT))
        }
        val found = LinkedHashMap<String, Answer>()
        val buffer = ByteArray(BUFFER)
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = (end - System.currentTimeMillis()).toInt()
            if (left <= 0) break
            socket.soTimeout = left
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                break
            }
            answer(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let { found.putIfAbsent(it.location, it) }
        }
        found.values.toList()
    }

    /**
     * The media servers answering a search of [timeoutMs], each with its description read: its name and the address
     * of that description, sorted by name; a server whose description cannot be read is left out.
     */
    fun servers(timeoutMs: Int = 3000): List<Pair<String, String>> = discover(timeoutMs).mapNotNull { answer ->
        runCatching {
            val connection = URI(answer.location).toURL().openConnection().apply {
                connectTimeout = DESCRIPTION_TIMEOUT_MS
                readTimeout = DESCRIPTION_TIMEOUT_MS
            }
            connection.getInputStream().use { UpnpDevice.parse(it, answer.location) }
        }.getOrNull()?.let { it.name to answer.location }
    }.distinctBy { it.second }.sortedBy { it.first.lowercase() }

    private const val BUFFER = 8192
    private const val DESCRIPTION_TIMEOUT_MS = 3000
}

/**
 * A media server as its description says (UPnP Device Architecture 1.1 §2.3): its [name] to show, its unique device
 * name, and the control URL of its ContentDirectory service, resolved against the description's URLBase or address.
 */
data class UpnpDevice(val name: String, val udn: String, val controlUrl: String) {
    companion object {
        private const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:"

        /** The description [xml] read from [location]; null where it holds no ContentDirectory service. */
        fun parse(xml: InputStream, location: String): UpnpDevice? {
            val document = Xml.parse(xml)
            val base = Xml.text(document.documentElement, "URLBase") ?: location
            // The first device that offers a content directory: the root or one embedded in it.
            val services = document.getElementsByTagNameNS("*", "service")
            for (i in 0 until services.length) {
                val service = services.item(i) as Element
                val type = Xml.text(service, "serviceType") ?: continue
                if (!type.startsWith(CONTENT_DIRECTORY)) continue
                val control = Xml.text(service, "controlURL") ?: continue
                val device = service.parentNode?.parentNode as? Element ?: continue
                return UpnpDevice(
                    name = Xml.text(device, "friendlyName") ?: URI(location).host,
                    udn = Xml.text(device, "UDN").orEmpty(),
                    controlUrl = URI(base).resolve(control.trim()).toString(),
                )
            }
            return null
        }
    }
}

/**
 * What a ContentDirectory holds (UPnP AV ContentDirectory:1, §2.8 DIDL-Lite): containers, which are folders, and items
 * with the resources to play.
 */
sealed class DidlEntry {
    abstract val id: String
    abstract val parentId: String
    abstract val title: String

    /** A container: a folder of entries. */
    data class Container(override val id: String, override val parentId: String, override val title: String) :
        DidlEntry()

    /**
     * An item: its first resource offered over HTTP — [url], its MIME type from the protocol info, its [size] (0 when
     * not given).
     */
    data class Item(
        override val id: String,
        override val parentId: String,
        override val title: String,
        val upnpClass: String,
        val url: String,
        val mime: String,
        val size: Long,
    ) : DidlEntry()

    companion object {
        private const val DIDL = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
        private const val DC = "http://purl.org/dc/elements/1.1/"
        private const val UPNP = "urn:schemas-upnp-org:metadata-1-0/upnp/"

        /** The entries of a DIDL-Lite document [xml], in its order; an item with no HTTP resource left out. */
        fun parse(xml: String): List<DidlEntry> {
            val document = Xml.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val root = document.documentElement
            val out = ArrayList<DidlEntry>()
            var node = root.firstChild
            while (node != null) {
                val e = node as? Element
                if (e != null && e.namespaceURI == DIDL) {
                    val id = e.getAttribute("id")
                    val parent = e.getAttribute("parentID")
                    val title = Xml.textNs(e, DC, "title").orEmpty()
                    when (e.localName) {
                        "container" -> out += Container(id, parent, title)
                        "item" -> resource(e)?.let { (url, mime, size) ->
                            out += Item(id, parent, title, Xml.textNs(e, UPNP, "class").orEmpty(), url, mime, size)
                        }
                    }
                }
                node = node.nextSibling
            }
            return out
        }

        // The first resource of [item] reached by HTTP GET: its URL, MIME type and size.
        private fun resource(item: Element): Triple<String, String, Long>? {
            val resources = item.getElementsByTagNameNS(DIDL, "res")
            for (i in 0 until resources.length) {
                val res = resources.item(i) as Element
                // protocolInfo: <protocol>:<network>:<contentFormat>:<additionalInfo> (ContentDirectory §2.5.x).
                val info = res.getAttribute("protocolInfo").split(':')
                if (info.firstOrNull() != "http-get") continue
                val url = res.textContent.trim().takeIf { it.isNotEmpty() } ?: continue
                return Triple(url, info.getOrNull(2).orEmpty(), res.getAttribute("size").toLongOrNull() ?: 0L)
            }
            return null
        }
    }
}

/** The namespace-aware XML reading UPnP's documents share. */
internal object Xml {
    fun parse(xml: InputStream): Document {
        // Servers put a byte order mark or blank lines before the XML declaration; the parser refuses either.
        val bytes = xml.readBytes()
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            start = 3
        }
        while (start < bytes.size && bytes[start].toInt().toChar().isWhitespace()) start++
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // A server's document never needs an external entity; none is fetched.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(ByteArrayInputStream(bytes, start, bytes.size - start))
    }

    /** The first [name] element's text below [element], in any namespace; null when absent or empty. */
    fun text(element: Element, name: String): String? =
        element.getElementsByTagNameNS("*", name).item(0)?.textContent?.trim()?.ifEmpty { null }

    /** The first [name] element's text in [namespace] below [element]. */
    fun textNs(element: Element, namespace: String, name: String): String? =
        element.getElementsByTagNameNS(namespace, name).item(0)?.textContent?.trim()?.ifEmpty { null }
}
