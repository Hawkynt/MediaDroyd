package com.hawkynt.mediadroyd.sources

import com.hawkynt.mediadroyd.library.Source
import com.hawkynt.mediadroyd.library.SourceKind
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A media server as the UPnP specifications write it: SSDP's answer (Device Architecture 1.1 §1.3.3), a device
 * description with the content directory on an embedded device (§2.3), and Browse answers in DIDL-Lite (ContentDirectory:1
 * §2.7.4, §2.8) — modelled on the specifications' own examples; no real server has been tried yet.
 */
class UpnpFsTest {

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>Living-room NAS</friendlyName>
            <UDN>uuid:4d696e69-444c-164e-9d41-001122334455</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <controlURL>/ctl/ConnectionMgr</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                <controlURL>/ctl/ContentDir</controlURL>
                <eventSubURL>/evt/ContentDir</eventSubURL>
                <SCPDURL>/ContentDir.xml</SCPDURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    private fun escape(xml: String) = xml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun browseResponse(didl: String, returned: Int, total: Int) = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
              <Result>${escape(didl)}</Result>
              <NumberReturned>$returned</NumberReturned>
              <TotalMatches>$total</TotalMatches>
              <UpdateID>7</UpdateID>
            </u:BrowseResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun didl(body: String) = """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"
          xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">$body</DIDL-Lite>
    """.trimIndent()

    private fun source() = Source("nas", "NAS", SourceKind.UPNP, url = server.url("/rootDesc.xml").toString())

    @Test
    fun `an SSDP answer gives the description's address, anything else nothing`() {
        val answer = Ssdp.answer(
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLocation: http://192.168.1.5:8200/rootDesc.xml" +
                "\r\nSERVER: Linux UPnP/1.0 MiniDLNA/1.3.3\r\nST: urn:schemas-upnp-org:service:ContentDirectory:1\r\n" +
                "USN: uuid:4d69::urn:schemas-upnp-org:service:ContentDirectory:1\r\n\r\n",
        )
        assertEquals("http://192.168.1.5:8200/rootDesc.xml", answer?.location)
        assertNull(Ssdp.answer("NOTIFY * HTTP/1.1\r\nLOCATION: http://x/\r\n\r\n"))
        assertTrue(Ssdp.search(Ssdp.TARGETS[0]).startsWith("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"))
        assertTrue(Ssdp.search(Ssdp.TARGETS[0]).contains("MAN: \"ssdp:discover\"\r\n"))
    }

    @Test
    fun `the description names the server and where its content directory is controlled`() {
        val device = UpnpDevice.parse(description.byteInputStream(), "http://192.168.1.5:8200/rootDesc.xml")!!
        assertEquals("Living-room NAS", device.name)
        assertEquals("http://192.168.1.5:8200/ctl/ContentDir", device.controlUrl)
        // A byte order mark and blank lines before the declaration, as some servers send, are no matter.
        val marked = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\r\n  $description".toByteArray()
        assertEquals("Living-room NAS", UpnpDevice.parse(marked.inputStream(), "http://h/d.xml")?.name)
    }

    @Test
    fun `containers are folders, items files named by title and type, in pages`() {
        server.enqueue(MockResponse.Builder().body(description).build())
        val first = didl(
            """<container id="64" parentID="0" restricted="1" childCount="2"><dc:title>Video</dc:title>""" +
                """<upnp:class>object.container.storageFolder</upnp:class></container>""",
        )
        val second = didl(
            """<item id="64${'$'}0" parentID="0" restricted="1"><dc:title>Heat (1995)</dc:title>""" +
                """<upnp:class>object.item.videoItem</upnp:class>""" +
                """<res size="10" protocolInfo="http-get:*:video/x-matroska:DLNA.ORG_OP=01">""" +
                """${server.url("/MediaItems/22.mkv")}</res></item>""" +
                """<item id="64${'$'}1" parentID="0"><dc:title>poster.jpg</dc:title>""" +
                """<upnp:class>object.item.imageItem.photo</upnp:class>""" +
                """<res protocolInfo="http-get:*:image/jpeg:*">${server.url("/MediaItems/23.jpg")}</res></item>""" +
                """<item id="64${'$'}2" parentID="0"><dc:title>Stream only</dc:title>""" +
                """<res protocolInfo="rtsp-rtp-udp:*:video/mp4:*">rtsp://x/y</res></item>""",
        )
        server.enqueue(MockResponse.Builder().body(browseResponse(first, 1, 4)).build())
        server.enqueue(MockResponse.Builder().body(browseResponse(second, 3, 4)).build())
        UpnpFs(source()).use { fs ->
            val entries = fs.root.listFiles()!!.associateBy { it.name }
            assertEquals(setOf("Video", "Heat (1995).mkv", "poster.jpg"), entries.keys)
            assertTrue(entries.getValue("Video").isDirectory)
            val heat = entries.getValue("Heat (1995).mkv")
            assertFalse(heat.isDirectory)
            assertEquals(10L, heat.length())
            assertEquals(server.url("/rootDesc.xml").toString() + "/64%240", heat.path)
            assertEquals(fs.root, heat.parentFile)
            assertEquals("poster.jpg", fs.root.child("poster.jpg").name)
        }
        assertEquals("GET", server.takeRequest().method)
        val browse = server.takeRequest()
        assertEquals("/ctl/ContentDir", browse.url.encodedPath)
        assertEquals("\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"", browse.headers["SOAPACTION"])
        val body = browse.body!!.utf8()
        assertTrue(body.contains("<ObjectID>0</ObjectID>") && body.contains("BrowseDirectChildren"))
        assertTrue(server.takeRequest().body!!.utf8().contains("<StartingIndex>1</StartingIndex>"))
    }

    @Test
    fun `a stored location is found again by its object, and read from an offset`() {
        server.enqueue(MockResponse.Builder().body(description).build())
        val item = didl(
            """<item id="64${'$'}0" parentID="64"><dc:title>Heat</dc:title>""" +
                """<res protocolInfo="http-get:*:video/mp4:*">${server.url("/MediaItems/22.mp4")}</res></item>""",
        )
        server.enqueue(MockResponse.Builder().body(browseResponse(item, 1, 1)).build())
        server.enqueue(MockResponse.Builder().code(206).body("6789").build())
        UpnpFs(source()).use { fs ->
            val file = fs.resolve(server.url("/rootDesc.xml").toString() + "/64%240")!!
            assertEquals("Heat.mp4", file.name)
            assertEquals(server.url("/rootDesc.xml").toString() + "/64", file.parentFile!!.path)
            assertEquals("6789", file.open(6).use { String(it.readBytes()) })
            assertNull(fs.resolve("http://elsewhere/x"))
        }
        server.takeRequest()
        assertTrue(server.takeRequest().body!!.utf8().contains("BrowseMetadata"))
        assertEquals("bytes=6-", server.takeRequest().headers["Range"])
    }

    @Test
    fun `a name takes the extension of its type, else of its address, never twice`() {
        assertEquals("Heat.mkv", UpnpFs.fileName("Heat", "video/x-matroska", "http://x/1"))
        assertEquals("Heat.avi", UpnpFs.fileName("Heat", "application/octet-stream", "http://x/1.AVI?x=1"))
        assertEquals("Heat.mp4", UpnpFs.fileName("Heat.mp4", "video/mp4", "http://x/1"))
        assertEquals("AC-DC Live", UpnpFs.fileName("AC/DC Live", "", "http://x/stream"))
    }
}
