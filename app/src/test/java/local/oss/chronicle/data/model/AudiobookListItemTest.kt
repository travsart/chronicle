package local.oss.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests for AudiobookListItem data class.
 * Verifies that the lightweight projection can be constructed with required fields.
 */
class AudiobookListItemTest {
    @Test
    fun `AudiobookListItem can be constructed with all required fields`() {
        // Arrange & Act
        val listItem = AudiobookListItem(
            id = "plex:12345",
            libraryId = "plex:library:1",
            source = 1L,
            title = "Test Book",
            titleSort = "Test Book",
            author = "Test Author",
            thumb = "/library/metadata/12345/thumb/1234567890",
            duration = 3600000L,
            progress = 1800000L,
            isCached = false,
            lastViewedAt = 1234567890L,
            viewCount = 1L,
            addedAt = 1234567890L,
            year = 2024,
            viewedLeafCount = 5L,
        )

        // Assert
        assertNotNull(listItem)
        assertEquals("plex:12345", listItem.id)
        assertEquals("plex:library:1", listItem.libraryId)
        assertEquals(1L, listItem.source)
        assertEquals("Test Book", listItem.title)
        assertEquals("Test Book", listItem.titleSort)
        assertEquals("Test Author", listItem.author)
        assertEquals("/library/metadata/12345/thumb/1234567890", listItem.thumb)
        assertEquals(3600000L, listItem.duration)
        assertEquals(1800000L, listItem.progress)
        assertEquals(false, listItem.isCached)
        assertEquals(1234567890L, listItem.lastViewedAt)
        assertEquals(1L, listItem.viewCount)
        assertEquals(1234567890L, listItem.addedAt)
        assertEquals(2024, listItem.year)
        assertEquals(5L, listItem.viewedLeafCount)
    }

    @Test
    fun `AudiobookListItem has all fields needed for list rendering`() {
        // This test documents the minimal fields needed for list display
        val listItem = AudiobookListItem(
            id = "plex:12345",
            libraryId = "plex:library:1",
            source = 1L,
            title = "Title",
            titleSort = "Title",
            author = "Author",
            thumb = "/thumb",
            duration = 1000L,
            progress = 500L,
            isCached = true,
            lastViewedAt = 0L,
            viewCount = 0L,
            addedAt = 0L,
            year = 0,
            viewedLeafCount = 0L,
        )

        // These are the fields used in list layouts:
        // - id: for item identification
        // - libraryId: for library-aware operations
        // - source: for source identification
        // - title: displayed in all list views
        // - titleSort: used for sorting
        // - author: displayed in all list views
        // - thumb: for thumbnail image
        // - duration: for progress calculation
        // - progress: for progress bar/display
        // - isCached: for offline mode filtering
        // - lastViewedAt: for "recently listened" sorting
        // - viewCount: for "not played" dog-ear indicator
        // - addedAt: for "recently added" sorting
        // - year: for year-based sorting
        // - viewedLeafCount: for play count sorting

        // Verify all necessary fields are present and accessible
        assertNotNull(listItem.id)
        assertNotNull(listItem.libraryId)
        assertNotNull(listItem.title)
        assertNotNull(listItem.author)
        assertNotNull(listItem.thumb)
    }
}
