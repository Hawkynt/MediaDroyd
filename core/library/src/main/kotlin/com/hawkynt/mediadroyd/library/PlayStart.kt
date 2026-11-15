package com.hawkynt.mediadroyd.library

/** Where a queue of videos — a show's episodes, a film's parts — picks up when played. */
object PlayStart {

    /**
     * The index in [queue] to start at: the video asked for ([path]), else the first not [watched] — so Play on a
     * show continues with the first episode not yet marked, its own resume point used if it has one, not a watched
     * episode that kept a resume point from a later look — else, everything watched, the first with a resume point
     * ([resumed]), else the start.
     */
    fun indexOf(
        queue: List<String>,
        path: String?,
        watched: (String) -> Boolean,
        resumed: (String) -> Boolean,
    ): Int =
        path?.let { p -> queue.indexOf(p) }?.takeIf { it >= 0 }
            ?: queue.indexOfFirst { !watched(it) }.takeIf { it >= 0 }
            ?: queue.indexOfFirst { resumed(it) }.takeIf { it >= 0 }
            ?: 0

    /**
     * The season a [show]'s details open on: the one holding the episode Play next starts at ([indexOf] over the
     * episodes in binge order, season by season) — where the household carries on, not the last season; null for a
     * show without episodes.
     */
    fun seasonOf(show: Show, watched: (String) -> Boolean, resumed: (String) -> Boolean): Int? {
        val order = show.seasons.sortedBy { it.number }.flatMap { season ->
            season.episodes.sortedBy { it.number }.map { season.number to it.videoPath }
        }
        if (order.isEmpty()) return null
        return order[indexOf(order.map { it.second }, null, watched, resumed)].first
    }
}
