package com.hawkynt.mediadroyd.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.hawkynt.mediadroyd.sources.Ssdp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A server announced on the local network: its name, and the address to connect to (a media server's description). */
data class LanServer(val name: String, val host: String)

/**
 * What the sources' screens need of the platform beside the household's memory: where a source's password is kept
 * (the system's own keeper, never the state file), what is let go of when a source changes (its connections) or goes
 * (its last scan too), and the SMB servers announcing themselves on the local network.
 */
interface SourceKeeper {
    /** The password kept for [sourceId], or null. */
    fun secretFor(sourceId: String): String?

    /** Keeps [secret] for [sourceId]; null or empty forgets it. */
    fun saveSecret(sourceId: String, secret: String?)

    /** Lets go of the connections open to [sourceId]: it changed. */
    fun release(sourceId: String)

    /** Lets go of everything kept for [sourceId] — its connections and its last scan: it is gone. */
    fun forget(sourceId: String)

    /** The SMB servers announcing themselves on the local network (mDNS, `_smb._tcp`) while [active]. */
    @Composable
    fun smbServers(active: Boolean): State<List<LanServer>>

    /**
     * The UPnP / DLNA media servers answering on the local network (SSDP) while [active], searched again every few
     * seconds; the same on every platform, as SSDP's answers come back to the sender alone.
     */
    @Composable
    fun upnpServers(active: Boolean): State<List<LanServer>> {
        val found = remember { mutableStateOf(emptyList<LanServer>()) }
        LaunchedEffect(active) {
            while (active) {
                val servers = withContext(Dispatchers.IO) {
                    runCatching { Ssdp.servers() }.getOrDefault(emptyList())
                }
                found.value = servers.map { (name, location) -> LanServer(name, location) }
                delay(UPNP_SEARCH_MS)
            }
        }
        return found
    }

    /** Keeps nothing and finds nothing. */
    object None : SourceKeeper {
        override fun secretFor(sourceId: String): String? = null

        override fun saveSecret(sourceId: String, secret: String?) = Unit

        override fun release(sourceId: String) = Unit

        override fun forget(sourceId: String) = Unit

        @Composable
        override fun smbServers(active: Boolean): State<List<LanServer>> = remember { mutableStateOf(emptyList()) }
    }
}

// How often the media servers are searched for again while the dialog shows them.
private const val UPNP_SEARCH_MS = 10_000L

/** The platform's [SourceKeeper]; each app provides its own at the root of its screens. */
val LocalSourceKeeper = staticCompositionLocalOf<SourceKeeper> { SourceKeeper.None }
