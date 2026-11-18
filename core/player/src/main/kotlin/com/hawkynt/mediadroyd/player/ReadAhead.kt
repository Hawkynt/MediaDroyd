package com.hawkynt.mediadroyd.player

/**
 * A film's demuxed packets read once and shared (#535): one reader [offer]s them in the file's order while they lie no
 * more than [aheadMs] past where playback is and the whole holds no more than [maxBytes]; playback reads them through
 * its [cursor] (which [Cursor.holds] them), the analysis taps the streams it wants through cursors of its own — the
 * film's sound for Whisper and the speech, without opening the file again. What lies more than [behindMs] behind
 * playback is given back ([release]), so a short seek back is still served from here ([Cursor.seek]); a tap that falls
 * that far behind is told it lost its place ([Next.Lost]) and reads for itself. A tap is handed its own reference to
 * each packet ([share]), which it gives back itself, so nothing it holds is given back under it. Thread-safe: the
 * reader, playback and the taps each on a thread of their own.
 *
 * With [trailMs] it trails another reader's buffer instead (Media3's, on Android): it never refuses a packet, keeps
 * only the last [trailMs] before the newest (and within [maxBytes]), and a tap the trail passes loses its place.
 */
class ReadAhead<T>(
    private val aheadMs: Long,
    private val maxBytes: Long,
    private val behindMs: Long,
    private val trailMs: Long = -1,
    private val share: (T) -> T = { it },
    private val release: (T) -> Unit,
) {
    /** What a cursor finds next. */
    sealed interface Next<out T> {
        /** The next packet of the cursor's streams. */
        data class Item<T>(val item: T, val ms: Long) : Next<T>

        /** Not read yet: the reader is behind, or the minute ahead is full. */
        data object Wait : Next<Nothing>

        /** The film has ended. */
        data object End : Next<Nothing>

        /** The cursor's place was given back (it fell behind) or everything was dropped ([reset]). */
        data object Lost : Next<Nothing>
    }

    private class Entry<T>(val item: T, val ms: Long, val bytes: Int, val stream: Int, val key: Boolean)

    private val lock = Object()
    private val entries = ArrayDeque<Entry<T>>()

    // The sequence number of entries' first: a cursor's place is a sequence number, stable as the front is given back.
    private var base = 0L
    private var bytes = 0L
    private var ended = false
    private var generation = 0
    private val cursors = ArrayList<Cursor<T>>()

    /** The time of the last packet read; Long.MIN_VALUE before the first. */
    val lastMs: Long get() = synchronized(lock) { entries.lastOrNull()?.ms ?: Long.MIN_VALUE }

    /** Where playback is: its cursor's last packet, else the first one held. */
    private fun positionMs(): Long =
        cursors.filter { it.holds }.mapNotNull { it.readMs }.minOrNull() ?: entries.firstOrNull()?.ms ?: 0L

    /**
     * Takes [item] ([bytes] long, at [ms], of [stream]; [key] where decoding can start there) where there is room;
     * false where the minute ahead or the memory is full — the reader then waits ([awaitRoom]) and offers it again.
     */
    fun offer(item: T, ms: Long, bytes: Int, stream: Int, key: Boolean): Boolean = synchronized(lock) {
        val trails = trailMs >= 0
        if (!trails && entries.isNotEmpty() && (ms - positionMs() > aheadMs || this.bytes + bytes > maxBytes)) {
            return false
        }
        entries.addLast(Entry(item, ms, bytes, stream, key))
        this.bytes += bytes
        if (trails) {
            while (entries.size > 1 && (entries.first().ms < ms - trailMs || this.bytes > maxBytes)) dropFirst()
        }
        lock.notifyAll()
        true
    }

    /** Waits up to [timeoutMs] for playback to move on (or anything else to change). */
    fun awaitRoom(timeoutMs: Long) = synchronized(lock) { lock.wait(timeoutMs) }

    /** The reader reached the film's end. */
    fun end() = synchronized(lock) {
        ended = true
        lock.notifyAll()
    }

    /** Everything given back — a seek outside what is held; the reader reads on from where it is sent. */
    fun reset() = synchronized(lock) {
        entries.forEach { release(it.item) }
        base += entries.size
        entries.clear()
        bytes = 0
        ended = false
        generation++
        cursors.forEach { it.readMs = null }
        lock.notifyAll()
    }

    /** Whether a packet at [ms] is held, or will be read without a seek (just past the last one). */
    fun covers(ms: Long): Boolean = synchronized(lock) {
        val first = entries.firstOrNull()?.ms ?: return false
        ms >= first && ms <= entries.last().ms + aheadMs
    }

    /**
     * A cursor at the first packet of [streams] (all where null) at or after [fromMs]; one that [holds] is playback,
     * whose place moves the minute ahead and keeps what it has not read.
     */
    fun cursor(fromMs: Long, streams: Set<Int>? = null, holds: Boolean = false): Cursor<T> = synchronized(lock) {
        val index = entries.indexOfFirst { it.ms >= fromMs && (streams == null || it.stream in streams) }
        val place = if (index < 0) base + entries.size else base + index
        Cursor(this, streams, holds, place, fromMs).also { cursors += it }
    }

    /** One reader of the packets: playback's, or a tap's. */
    class Cursor<T> internal constructor(
        private val owner: ReadAhead<T>,
        private val streams: Set<Int>?,
        /** Whether this cursor keeps what it has not read: playback's. */
        val holds: Boolean,
        internal var place: Long,
        private var fromMs: Long,
    ) {
        // The reset it last joined after.
        private var joined = owner.generation

        /** The time of the last packet handed out; null before the first. */
        internal var readMs: Long? = null

        /** The next packet of its streams, or why there is none now. */
        fun next(): Next<T> = with(owner) {
            synchronized(lock) {
                if (generation != joined || place < base) return Next.Lost
                while (place - base < entries.size) {
                    val entry = entries[(place - base).toInt()]
                    place++
                    if (entry.ms < fromMs || streams != null && entry.stream !in streams) continue
                    readMs = entry.ms
                    if (holds) giveBack()
                    lock.notifyAll()
                    return Next.Item(if (holds) entry.item else share(entry.item), entry.ms)
                }
                if (ended) Next.End else Next.Wait
            }
        }

        /** Waits up to [timeoutMs] for something to read. */
        fun await(timeoutMs: Long) = synchronized(owner.lock) { owner.lock.wait(timeoutMs) }

        /**
         * Goes to [ms] inside what is held: to the last key frame of [keyStream] at or before it, everything from
         * there handed out again; false (staying where it is) where it is not held.
         */
        fun seek(ms: Long, keyStream: Int): Boolean = with(owner) {
            synchronized(lock) {
                val index = entries.indexOfLast { it.stream == keyStream && it.key && it.ms <= ms }
                if (index < 0 || entries.last().ms < ms) return false
                place = base + index
                fromMs = Long.MIN_VALUE
                readMs = entries[index].ms
                true
            }
        }

        /** Rejoins after a [reset]: from the next packet read on. */
        fun rejoin() = with(owner) {
            synchronized(lock) {
                joined = generation
                place = base + entries.size
                fromMs = Long.MIN_VALUE
            }
        }

        /** Stops reading: a tap closed. */
        fun close() = synchronized(owner.lock) { owner.cursors.remove(this) }
    }

    // The front given back while it lies more than behindMs behind playback and playback has read it.
    private fun giveBack() {
        val position = positionMs()
        val held = cursors.filter { it.holds }
        while (entries.isNotEmpty()) {
            val first = entries.first()
            if (first.ms >= position - behindMs || held.any { it.place <= base }) break
            dropFirst()
        }
    }

    private fun dropFirst() {
        val first = entries.removeFirst()
        base++
        bytes -= first.bytes
        release(first.item)
    }
}
