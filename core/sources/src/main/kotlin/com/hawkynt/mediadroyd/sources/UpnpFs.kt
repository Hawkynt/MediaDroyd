package com.hawkynt.mediadroyd.sources

import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.library.Source
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A UPnP / DLNA media server (#454) browsed through its ContentDirectory (UPnP AV ContentDirectory:1 §2.7.4: Browse,
 * in pages) and played over HTTP with ranged GETs. [Source.url] is the server's description address, as SSDP found
 * it; its containers are the folders, its items the files — each named by its title with the extension its MIME type
 * calls for, so the library takes a video for a video. Locations are the description's address and the object's id:
 * `<url>/<id, URL-encoded>`; the root container ("0") is the address itself.
 */
class UpnpFs(private val source: Source) : RemoteFs {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    internal val rootUri: String = source.rootUri

    // The server's description, read once.
    private val device: UpnpDevice by lazy {
        val request = Request.Builder().url(source.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("${source.url} answered ${response.code}")
            UpnpDevice.parse(response.body.byteStream(), source.url)
                ?: throw IOException("${source.url} is no media server")
        }
    }

    /** The server's own name. */
    val name: String get() = device.name

    override val root: FsFile get() = UpnpFile(this, DidlEntry.Container(ROOT, "-1", device.name))

    /** The location of the object [id]. */
    internal fun locationOf(id: String): String =
        if (id == ROOT) rootUri else "$rootUri/" + URLEncoder.encode(id, Charsets.UTF_8)

    /** The children of the container [id], every page of them. */
    internal fun children(id: String): List<DidlEntry> {
        val out = ArrayList<DidlEntry>()
        var start = 0
        while (true) {
            val page = browse(id, "BrowseDirectChildren", start, PAGE)
            out += page.entries
            start += page.returned
            if (page.returned == 0 || start >= page.total) break
        }
        return out
    }

    /** The object [id] itself. */
    internal fun metadata(id: String): DidlEntry? = browse(id, "BrowseMetadata", 0, 0).entries.firstOrNull()

    private class Page(val entries: List<DidlEntry>, val returned: Int, val total: Int)

    private fun browse(id: String, flag: String, start: Int, count: Int): Page {
        val body = BROWSE_BODY.replace("%OBJECT%", escape(id)).replace("%FLAG%", flag)
            .replace("%START%", start.toString()).replace("%COUNT%", count.toString())
        val request = Request.Builder().url(device.controlUrl)
            .header("SOAPACTION", "\"$SERVICE#Browse\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("${device.name} answered ${response.code} to Browse")
            val envelope = Xml.parse(response.body.byteStream()).documentElement
            val result = Xml.text(envelope, "Result").orEmpty()
            val entries = if (result.isEmpty()) emptyList() else DidlEntry.parse(result)
            val returned = Xml.text(envelope, "NumberReturned")?.toIntOrNull() ?: entries.size
            val total = Xml.text(envelope, "TotalMatches")?.toIntOrNull()?.takeIf { it > 0 } ?: (start + returned)
            return Page(entries, returned, total)
        }
    }

    /** GET [url] from byte [offset] on. */
    internal fun get(url: String, offset: Long): InputStream {
        val request = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("${device.name} answered ${response.code} for $url")
        }
        val stream = response.body.byteStream()
        // A server that ignores the range sends the whole file; the start is skipped here then.
        if (offset > 0 && response.code != PARTIAL_CONTENT) stream.skipNBytes(offset)
        return stream
    }

    override fun resolve(location: String): FsFile? {
        if (location == rootUri) return root
        if (!location.startsWith("$rootUri/")) return null
        val id = URLDecoder.decode(location.removePrefix("$rootUri/"), Charsets.UTF_8)
        return runCatching { metadata(id) }.getOrNull()?.let { UpnpFile(this, it) }
    }

    override fun close() {
        client.connectionPool.evictAll()
    }

    companion object {
        /** The root container's id (ContentDirectory §2.8.1). */
        const val ROOT = "0"
        private const val SERVICE = "urn:schemas-upnp-org:service:ContentDirectory:1"
        private const val PAGE = 200
        private const val PARTIAL_CONTENT = 206
        private const val TIMEOUT_S = 20L

        /** The Browse request, from browse.xml beside this class. */
        private val BROWSE_BODY: String = checkNotNull(UpnpFs::class.java.getResourceAsStream("browse.xml"))
            .bufferedReader().use { it.readText().trimEnd() }

        private fun escape(text: String) =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** The extensions MIME types call for (IANA's registry and the types DLNA servers send). */
        private val EXTENSIONS = mapOf(
            "video/x-matroska" to "mkv", "video/mp4" to "mp4", "video/x-m4v" to "m4v", "video/quicktime" to "mov",
            "video/x-msvideo" to "avi", "video/avi" to "avi", "video/mpeg" to "mpg", "video/mp2t" to "ts",
            "video/vnd.dlna.mpeg-tts" to "ts", "video/x-ms-wmv" to "wmv", "video/webm" to "webm",
            "video/x-flv" to "flv", "video/3gpp" to "3gp", "audio/mpeg" to "mp3", "audio/mp4" to "m4a",
            "audio/flac" to "flac", "audio/x-flac" to "flac", "audio/ogg" to "ogg", "audio/x-wav" to "wav",
            "audio/wav" to "wav", "image/jpeg" to "jpg", "image/png" to "png", "text/srt" to "srt",
            "application/x-subrip" to "srt",
        )

        /**
         * The file name an item is shown and recognised by: its [title] — slashes, which cannot be in a name, made
         * dashes — with the extension its [mime] type calls for, else its [url]'s; nothing added where the title ends
         * with it already.
         */
        fun fileName(title: String, mime: String, url: String): String {
            val base = title.replace('/', '-').trim().ifEmpty { "Untitled" }
            val extension = EXTENSIONS[mime.lowercase().substringBefore(';')]
                ?: url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
                    .lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
                ?: return base
            return if (base.endsWith(".$extension", ignoreCase = true)) base else "$base.$extension"
        }
    }
}

/** A container or item of a media server. */
internal class UpnpFile(private val fs: UpnpFs, private val entry: DidlEntry) : FsFile {
    override val name: String
        get() = when (entry) {
            is DidlEntry.Item -> UpnpFs.fileName(entry.title, entry.mime, entry.url)
            is DidlEntry.Container -> entry.title.replace('/', '-')
        }
    override val path: String get() = fs.locationOf(entry.id)
    override val isDirectory: Boolean get() = entry is DidlEntry.Container
    override val parentFile: FsFile?
        get() = when {
            entry.id == UpnpFs.ROOT -> null
            entry.parentId == UpnpFs.ROOT || entry.parentId.isEmpty() -> fs.root
            else -> UpnpFile(fs, DidlEntry.Container(entry.parentId, "", ""))
        }

    override fun length(): Long = (entry as? DidlEntry.Item)?.size ?: 0L

    override fun lastModified(): Long = 0L

    // A container's children, read once: the library looks for art beside each video by name.
    private val children: List<UpnpFile>? by lazy {
        if (entry !is DidlEntry.Container) null else runCatching { fs.children(entry.id).map { UpnpFile(fs, it) } }
            .getOrNull()
    }

    override fun listFiles(): List<FsFile>? = children

    override fun child(name: String): FsFile =
        children?.firstOrNull { it.name == name } ?: Missing(this, name)

    override fun open(offset: Long): InputStream =
        (entry as? DidlEntry.Item)?.let { fs.get(it.url, offset) } ?: throw FileNotFoundException("$path is a folder")

    override fun equals(other: Any?): Boolean = other is UpnpFile && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = path

    /** An entry asked for by name that the container does not hold. */
    private class Missing(override val parentFile: FsFile, override val name: String) : FsFile {
        override val path: String get() = parentFile.path + "/" + URLEncoder.encode(name, Charsets.UTF_8)
        override val isDirectory: Boolean get() = false
        override fun length(): Long = 0L
        override fun lastModified(): Long = 0L
        override fun listFiles(): List<FsFile>? = null
        override fun child(name: String): FsFile = Missing(this, name)
        override fun open(offset: Long): InputStream = throw FileNotFoundException(path)
    }
}
