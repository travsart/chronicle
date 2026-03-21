package local.oss.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterFilterTest {
    @Test
    fun `filterTransitionMarkers drops sub-second boundary markers when track has real chapters`() {
        val chapters =
            listOf(
                Chapter(
                    title = "Chapter 1",
                    id = 1,
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 1_054_550L,
                    trackId = "plex:65798",
                    bookId = "plex:65797",
                ),
                Chapter(
                    title = "Chapter 2",
                    id = 1,
                    index = 2,
                    startTimeOffset = 1_054_550L,
                    endTimeOffset = 1_054_600L,
                    trackId = "plex:65798",
                    bookId = "plex:65797",
                ),
                Chapter(
                    title = "Chapter 2",
                    id = 2,
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 3_059_410L,
                    trackId = "plex:65799",
                    bookId = "plex:65797",
                ),
                Chapter(
                    title = "Chapter 3",
                    id = 2,
                    index = 2,
                    startTimeOffset = 3_059_410L,
                    endTimeOffset = 3_059_460L,
                    trackId = "plex:65799",
                    bookId = "plex:65797",
                ),
            )

        val filtered = chapters.filterTransitionMarkers()

        assertEquals(listOf("Chapter 1", "Chapter 2"), filtered.map { it.title })
        assertEquals(listOf(1L, 1L), filtered.map { it.index })
    }

    @Test
    fun `filterTransitionMarkers recreates hydrogen sonata duplicate marker pattern from logs`() {
        val chapters =
            listOf(
                Chapter(
                    title = "Chapter 1",
                    id = 4041,
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 1_054_550L,
                    trackId = "plex:65798",
                    bookId = "plex:65797",
                ),
                Chapter(
                    title = "Chapter 2",
                    id = 4041,
                    index = 2,
                    startTimeOffset = 1_054_550L,
                    endTimeOffset = 1_054_600L,
                    trackId = "plex:65798",
                    bookId = "plex:65797",
                ),
                Chapter(
                    title = "Chapter 2",
                    id = 20664,
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 3_059_410L,
                    trackId = "plex:65799",
                    bookId = "plex:65797",
                ),
                Chapter(
                    title = "Chapter 3",
                    id = 20664,
                    index = 2,
                    startTimeOffset = 3_059_410L,
                    endTimeOffset = 3_059_460L,
                    trackId = "plex:65799",
                    bookId = "plex:65797",
                ),
                Chapter(
                    title = "Chapter 30",
                    id = 184304,
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 584_660L,
                    trackId = "plex:65827",
                    bookId = "plex:65797",
                ),
            )

        val filtered = chapters.filterTransitionMarkers()

        assertEquals(3, filtered.size)
        assertEquals(listOf(1L, 1L, 1L), filtered.map { it.index })
        assertEquals(
            listOf("plex:65798", "plex:65799", "plex:65827"),
            filtered.map { it.trackId },
        )
        assertEquals(
            listOf(1_054_550L, 3_059_410L, 584_660L),
            filtered.map { it.endTimeOffset - it.startTimeOffset },
        )
    }

    @Test
    fun `filterTransitionMarkers keeps short chapters when no meaningful chapter exists for track`() {
        val chapters =
            listOf(
                Chapter(
                    title = "Short Intro",
                    id = 10,
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 500L,
                    trackId = "plex:1",
                    bookId = "plex:book",
                ),
            )

        val filtered = chapters.filterTransitionMarkers()

        assertEquals(chapters, filtered)
    }
}