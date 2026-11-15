package com.hawkynt.mediadroyd.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayStartTest {

    private val queue = listOf("e1", "e2", "e3", "e4")

    @Test
    fun `the video asked for is where it starts`() {
        assertEquals(2, PlayStart.indexOf(queue, "e3", watched = { true }, resumed = { false }))
    }

    @Test
    fun `a show resumes at its first episode not marked watched, not at a watched one with a resume point`() {
        val watched = setOf("e1", "e2")
        assertEquals(2, PlayStart.indexOf(queue, null, watched = { it in watched }, resumed = { it == "e1" }))
    }

    @Test
    fun `everything watched, it picks up where a resume point is, else at the start`() {
        assertEquals(1, PlayStart.indexOf(queue, null, watched = { true }, resumed = { it == "e2" }))
        assertEquals(0, PlayStart.indexOf(queue, null, watched = { true }, resumed = { false }))
        assertEquals(0, PlayStart.indexOf(emptyList(), null, watched = { true }, resumed = { false }))
    }

    @Test
    fun `a video asked for that is not in the queue is no reason to start elsewhere than the rules say`() {
        assertEquals(0, PlayStart.indexOf(queue, "other", watched = { false }, resumed = { false }))
    }

    private val show = Show(
        title = "Supernatural",
        path = "/tv/Supernatural",
        seasons = listOf(
            Season(3, listOf(Episode(1, videoPath = "s3e1"), Episode(2, videoPath = "s3e2"))),
            Season(1, listOf(Episode(2, videoPath = "s1e2"), Episode(1, videoPath = "s1e1"))),
            Season(2, listOf(Episode(1, videoPath = "s2e1"), Episode(2, videoPath = "s2e2"))),
        ),
    )

    @Test
    fun `a show's details open on the season of its first episode not yet watched, not on the last`() {
        val watched = setOf("s1e1", "s1e2", "s2e1")
        assertEquals(2, PlayStart.seasonOf(show, watched = { it in watched }, resumed = { false }))
    }

    @Test
    fun `everything watched, the details open where a resume point is, else on the first season`() {
        assertEquals(3, PlayStart.seasonOf(show, watched = { true }, resumed = { it == "s3e2" }))
        assertEquals(1, PlayStart.seasonOf(show, watched = { true }, resumed = { false }))
        assertEquals(null, PlayStart.seasonOf(Show(title = "Empty", path = "/tv/Empty"), { false }, { false }))
    }
}
