package local.oss.chronicle.data.sources.plex.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

/**
 * Unit tests for PlayQueueResponse models and extension functions.
 *
 * These tests verify the parsing and mapping logic for Plex play queue responses,
 * which are critical for dashboard activity reporting to work correctly.
 */
@RunWith(MockitoJUnitRunner::class)
class PlayQueueResponseTest {

    // ========================================
    // toPlayQueueItemMap() Tests
    // ========================================

    @Test
    fun `toPlayQueueItemMap maps ratingKey to playQueueItemId correctly`() {
        // Given: A response with multiple items
        val items = listOf(
            PlayQueueItem(ratingKey = "12345", playQueueItemID = 1001L, title = "Track 1"),
            PlayQueueItem(ratingKey = "67890", playQueueItemID = 1002L, title = "Track 2"),
            PlayQueueItem(ratingKey = "11111", playQueueItemID = 1003L, title = "Track 3"),
        )

        val mediaContainer = PlayQueueMediaContainer(
            playQueueID = 100L,
            playQueueSelectedItemID = 1001L,
            metadata = items,
        )

        val response = PlayQueueResponseWrapper(mediaContainer = mediaContainer)

        // When: Converting to map
        val map = response.toPlayQueueItemMap()

        // Then: All items are mapped with plex: prefix
        assertThat(map).hasSize(3)
        assertThat(map["plex:12345"]).isEqualTo(1001L)
        assertThat(map["plex:67890"]).isEqualTo(1002L)
        assertThat(map["plex:11111"]).isEqualTo(1003L)
    }

    @Test
    fun `toPlayQueueItemMap handles null metadata list`() {
        // Given: A response with null metadata
        val mediaContainer = PlayQueueMediaContainer(
            playQueueID = 100L,
            playQueueSelectedItemID = -1L,
            metadata = null,
        )

        val response = PlayQueueResponseWrapper(mediaContainer = mediaContainer)

        // When: Converting to map
        val map = response.toPlayQueueItemMap()

        // Then: Returns empty map
        assertThat(map).isEmpty()
    }

    @Test
    fun `toPlayQueueItemMap handles null mediaContainer`() {
        // Given: A response with null mediaContainer
        val response = PlayQueueResponseWrapper(mediaContainer = null)

        // When: Converting to map
        val map = response.toPlayQueueItemMap()

        // Then: Returns empty map
        assertThat(map).isEmpty()
    }

    @Test
    fun `toPlayQueueItemMap handles empty metadata list`() {
        // Given: A response with empty metadata list
        val mediaContainer = PlayQueueMediaContainer(
            playQueueID = 100L,
            playQueueSelectedItemID = -1L,
            metadata = emptyList(),
        )

        val response = PlayQueueResponseWrapper(mediaContainer = mediaContainer)

        // When: Converting to map
        val map = response.toPlayQueueItemMap()

        // Then: Returns empty map
        assertThat(map).isEmpty()
    }

    @Test
    fun `toPlayQueueItemMap handles items with empty ratingKey`() {
        // Given: Items with empty ratingKey
        val items = listOf(
            PlayQueueItem(ratingKey = "", playQueueItemID = 1001L, title = "Track 1"),
            PlayQueueItem(ratingKey = "12345", playQueueItemID = 1002L, title = "Track 2"),
        )

        val mediaContainer = PlayQueueMediaContainer(
            playQueueID = 100L,
            playQueueSelectedItemID = 1001L,
            metadata = items,
        )

        val response = PlayQueueResponseWrapper(mediaContainer = mediaContainer)

        // When: Converting to map
        val map = response.toPlayQueueItemMap()

        // Then: Empty ratingKey is mapped as "plex:"
        assertThat(map).hasSize(2)
        assertThat(map["plex:"]).isEqualTo(1001L)
        assertThat(map["plex:12345"]).isEqualTo(1002L)
    }

    @Test
    fun `toPlayQueueItemMap handles items with default playQueueItemID`() {
        // Given: Items with default (-1) playQueueItemID
        val items = listOf(
            PlayQueueItem(ratingKey = "12345", playQueueItemID = -1L, title = "Track 1"),
            PlayQueueItem(ratingKey = "67890", playQueueItemID = 1002L, title = "Track 2"),
        )

        val mediaContainer = PlayQueueMediaContainer(
            playQueueID = 100L,
            playQueueSelectedItemID = -1L,
            metadata = items,
        )

        val response = PlayQueueResponseWrapper(mediaContainer = mediaContainer)

        // When: Converting to map
        val map = response.toPlayQueueItemMap()

        // Then: -1 values are preserved in the map
        assertThat(map).hasSize(2)
        assertThat(map["plex:12345"]).isEqualTo(-1L)
        assertThat(map["plex:67890"]).isEqualTo(1002L)
    }

    @Test
    fun `toPlayQueueItemMap handles single item response`() {
        // Given: A response with a single item
        val items = listOf(
            PlayQueueItem(ratingKey = "12345", playQueueItemID = 1001L, title = "Single Track"),
        )

        val mediaContainer = PlayQueueMediaContainer(
            playQueueID = 100L,
            playQueueSelectedItemID = 1001L,
            metadata = items,
        )

        val response = PlayQueueResponseWrapper(mediaContainer = mediaContainer)

        // When: Converting to map
        val map = response.toPlayQueueItemMap()

        // Then: Single item is mapped correctly
        assertThat(map).hasSize(1)
        assertThat(map["plex:12345"]).isEqualTo(1001L)
    }

    @Test
    fun `toPlayQueueItemMap adds plex prefix consistently`() {
        // Given: Multiple tracks with numeric rating keys
        val items = listOf(
            PlayQueueItem(ratingKey = "1", playQueueItemID = 2001L, title = "Track 1"),
            PlayQueueItem(ratingKey = "999", playQueueItemID = 2002L, title = "Track 999"),
            PlayQueueItem(ratingKey = "123456789", playQueueItemID = 2003L, title = "Track Long"),
        )

        val mediaContainer = PlayQueueMediaContainer(
            playQueueID = 200L,
            playQueueSelectedItemID = 2001L,
            metadata = items,
        )

        val response = PlayQueueResponseWrapper(mediaContainer = mediaContainer)

        // When: Converting to map
        val map = response.toPlayQueueItemMap()

        // Then: All keys have plex: prefix
        assertThat(map.keys).containsExactly("plex:1", "plex:999", "plex:123456789")
        assertThat(map["plex:1"]).isEqualTo(2001L)
        assertThat(map["plex:999"]).isEqualTo(2002L)
        assertThat(map["plex:123456789"]).isEqualTo(2003L)
    }

    // ========================================
    // Data Class Tests
    // ========================================

    @Test
    fun `PlayQueueResponseWrapper handles all fields`() {
        // Given: A complete response structure
        val items = listOf(
            PlayQueueItem(ratingKey = "12345", playQueueItemID = 1001L, title = "Track 1"),
        )

        val mediaContainer = PlayQueueMediaContainer(
            playQueueID = 100L,
            playQueueSelectedItemID = 1001L,
            metadata = items,
        )

        val response = PlayQueueResponseWrapper(mediaContainer = mediaContainer)

        // Then: All fields are accessible
        assertThat(response.mediaContainer).isNotNull()
        assertThat(response.mediaContainer?.playQueueID).isEqualTo(100L)
        assertThat(response.mediaContainer?.playQueueSelectedItemID).isEqualTo(1001L)
        assertThat(response.mediaContainer?.metadata).hasSize(1)
    }

    @Test
    fun `PlayQueueMediaContainer has sensible defaults`() {
        // Given: MediaContainer with default values
        val container = PlayQueueMediaContainer()

        // Then: Defaults are set correctly
        assertThat(container.playQueueID).isEqualTo(-1L)
        assertThat(container.playQueueSelectedItemID).isEqualTo(-1L)
        assertThat(container.metadata).isNull()
    }

    @Test
    fun `PlayQueueItem has sensible defaults`() {
        // Given: PlayQueueItem with default values
        val item = PlayQueueItem()

        // Then: Defaults are set correctly
        assertThat(item.ratingKey).isEmpty()
        assertThat(item.playQueueItemID).isEqualTo(-1L)
        assertThat(item.title).isEmpty()
    }
}
