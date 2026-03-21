package local.oss.chronicle.features.player

import kotlinx.coroutines.runBlocking
import local.oss.chronicle.data.model.MediaItemTrack
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Before
import org.junit.Test

class TrackListStateManagerTest {
    val exampleTrackList =
        listOf(
            MediaItemTrack(id = "plex:1", libraryId = "plex:lib:1", progress = 0, duration = 50, lastViewedAt = 1),
            MediaItemTrack(id = "plex:2", libraryId = "plex:lib:1", progress = 25, duration = 50, lastViewedAt = 3),
            MediaItemTrack(id = "plex:3", libraryId = "plex:lib:1", progress = 0, duration = 50, lastViewedAt = 0),
        )

    val manager = TrackListStateManager()

    @Before
    fun setupManager() {
        manager.trackList = exampleTrackList
    }

    @Test
    fun updatePosition() {
    }

    @Test
    fun seekToActiveTrack() =
        runBlocking {
            manager.seekToActiveTrack()

            // assert track index correct
            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((1), (25L))),
            )
        }

    @Test
    fun `test seeking forwards within track`() =
        runBlocking {
            manager.updatePosition(1, 25)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((1), (25L))),
            )

            manager.seekByRelative(20)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((1), (45L))),
            )
        }

    @Test
    fun `test seeking forwards into to next track`() =
        runBlocking {
            manager.updatePosition(1, 25)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((1), (25L))),
            )

            manager.seekByRelative(40)

            assertThat(
                Pair(manager.currentTrackProgress, manager.currentTrackIndex),
                `is`(Pair(15L, 2)),
            )
        }

    @Test
    fun `test seeking forwards beyond end of track list`() =
        runBlocking {
            manager.updatePosition(1, 25)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((1), (25L))),
            )

            manager.seekByRelative(1000)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((2), (50L))),
            )
        }

    @Test
    fun `test seeking forwards beyond end of track list, starting with finished track`() =
        runBlocking {
            manager.updatePosition(2, 50)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((2), (50L))),
            )

            manager.seekByRelative(1000)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((2), (50L))),
            )
        }

    @Test
    fun `test seeking backwards within track`() =
        runBlocking {
            manager.updatePosition(1, 25)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((1), (25L))),
            )

            manager.seekByRelative(-15)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair(1, 10L)),
            )
        }

    @Test
    fun `test seeking backwards into previous track`() =
        runBlocking {
            manager.updatePosition(1, 25)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((1), (25L))),
            )

            manager.seekByRelative(-40)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((0), (35L))),
            )
        }

    @Test
    fun `test seeking backwards starting at index == 0, offset == 0`() =
        runBlocking {
            manager.updatePosition(0, 0)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((0), (0L))),
            )

            manager.seekByRelative(-20)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((0), (0L))),
            )
        }

    @Test
    fun `test seeking backwards beyond start of track list`() =
        runBlocking {
            manager.updatePosition(1, 25)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((1), (25L))),
            )

            manager.seekByRelative(-1000)

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((0), (0L))),
            )
        }

    @Test
    fun seekToTrack() =
        runBlocking {
            manager.seekToActiveTrack()

            assertThat(
                Pair(manager.currentTrackIndex, manager.currentTrackProgress),
                `is`(Pair((1), (25L))),
            )
        }

    @Test
    fun `updatePosition handles out of bounds index by clamping to last valid index`() =
        runBlocking {
            // Given: manager with 3 tracks
            // When: attempt to set index beyond track list size
            manager.updatePosition(10, 25)

            // Then: should clamp to last valid index instead of throwing
            assertThat(
                manager.currentTrackIndex,
                `is`(2), // Last valid index for 3 tracks
            )
            assertThat(
                manager.currentTrackProgress,
                `is`(25L),
            )
        }

    @Test
    fun `updatePosition handles negative index by clamping to zero`() =
        runBlocking {
            // Given: manager with tracks
            // When: attempt to set negative index
            manager.updatePosition(-1, 25)

            // Then: should clamp to 0 instead of throwing
            assertThat(
                manager.currentTrackIndex,
                `is`(0),
            )
            assertThat(
                manager.currentTrackProgress,
                `is`(25L),
            )
        }

    @Test
    fun `updatePosition handles empty track list gracefully`() =
        runBlocking {
            // Given: manager with empty track list
            val emptyManager = TrackListStateManager()
            emptyManager.setTrackList(emptyList())

            // When: attempt to update position on empty list
            // Then: should not throw, just return
            emptyManager.updatePosition(0, 25)

            // Verify state remains sensible
            assertThat(
                emptyManager.trackList.size,
                `is`(0),
            )
        }

    @Test
    fun `updatePositionBlocking handles out of bounds index`() {
        // Given: manager with 3 tracks
        // When: attempt to set index beyond track list size using blocking variant
        manager.updatePositionBlocking(10, 25)

        // Then: should clamp to last valid index instead of throwing
        assertThat(
            manager.currentTrackIndex,
            `is`(2), // Last valid index for 3 tracks
        )
        assertThat(
            manager.currentTrackProgress,
            `is`(25L),
        )
    }
}
