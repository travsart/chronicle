package local.oss.chronicle.features.player

import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.Chapter
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.sources.plex.PlexMediaSource
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.nullValue
import org.junit.Test

class PlaybackStateTest {
    // Test data - 3 tracks of 60 seconds each (180 seconds total)
    private val testTracks =
        listOf(
            MediaItemTrack(id = "plex:1", libraryId = "plex:lib:1", title = "Track 1", duration = 60_000L, index = 1),
            MediaItemTrack(id = "plex:2", libraryId = "plex:lib:1", title = "Track 2", duration = 60_000L, index = 2),
            MediaItemTrack(id = "plex:3", libraryId = "plex:lib:1", title = "Track 3", duration = 60_000L, index = 3),
        )

    // Test chapters with TRACK-RELATIVE offsets (real-world Plex structure)
    // Track 1 (60s): Chapter 1 (0-60s)
    // Track 2 (60s): Chapter 2 (0-30s), Chapter 3 (30-60s)
    // Track 3 (60s): Chapter 4 (0-60s)
    private val testChapters =
        listOf(
            Chapter(
                id = 1,
                title = "Chapter 1",
                index = 1,
                startTimeOffset = 0L,
                endTimeOffset = 60_000L,
                trackId = "plex:1",
                bookId = "plex:100",
            ),
            Chapter(
                id = 2,
                title = "Chapter 2",
                index = 2,
                startTimeOffset = 0L,
                endTimeOffset = 30_000L,
                trackId = "plex:2",
                bookId = "plex:100",
            ),
            Chapter(
                id = 3,
                title = "Chapter 3",
                index = 3,
                startTimeOffset = 30_000L,
                endTimeOffset = 60_000L,
                trackId = "plex:2",
                bookId = "plex:100",
            ),
            Chapter(
                id = 4,
                title = "Chapter 4",
                index = 4,
                startTimeOffset = 0L,
                endTimeOffset = 60_000L,
                trackId = "plex:3",
                bookId = "plex:100",
            ),
        )

    private val testAudiobook =
        Audiobook(
            id = "plex:100",
            libraryId = "plex:lib:1",
            source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
            title = "Test Audiobook",
            author = "Test Author",
            duration = 180_000L,
            progress = 30_000L,
        )

    // =====================
    // Empty State Tests
    // =====================

    @Test
    fun `EMPTY state has default values`() {
        val state = PlaybackState.EMPTY

        assertThat(state.audiobook, `is`(nullValue()))
        assertThat(state.tracks, `is`(emptyList()))
        assertThat(state.chapters, `is`(emptyList()))
        assertThat(state.currentTrackIndex, `is`(0))
        assertThat(state.currentTrackPositionMs, `is`(0L))
        assertThat(state.isPlaying, `is`(false))
        assertThat(state.playbackSpeed, `is`(1.0f))
        assertThat(state.hasMedia, `is`(false))
    }

    // =====================
    // Factory Method Tests
    // =====================

    @Test
    fun `fromAudiobook creates state with correct values`() {
        val state =
            PlaybackState.fromAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
                startTrackIndex = 1,
                startPositionMs = 15_000L,
            )

        assertThat(state.audiobook, `is`(testAudiobook))
        assertThat(state.tracks, `is`(testTracks))
        assertThat(state.chapters, `is`(testChapters))
        assertThat(state.currentTrackIndex, `is`(1))
        assertThat(state.currentTrackPositionMs, `is`(15_000L))
        assertThat(state.playbackSpeed, `is`(1.0f))
        assertThat(state.isPlaying, `is`(false))
        assertThat(state.hasMedia, `is`(true))
    }

    @Test
    fun `fromAudiobook coerces negative startPositionMs to 0`() {
        val state =
            PlaybackState.fromAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
                startPositionMs = -100L,
            )

        assertThat(state.currentTrackPositionMs, `is`(0L))
    }

    @Test
    fun `fromAudiobook coerces out-of-bounds startTrackIndex`() {
        val state =
            PlaybackState.fromAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
                startTrackIndex = 99,
            )

        assertThat(state.currentTrackIndex, `is`(2)) // last index
    }

    @Test
    fun `fromAudiobook handles negative startTrackIndex`() {
        val state =
            PlaybackState.fromAudiobook(
                audiobook = testAudiobook,
                tracks = testTracks,
                chapters = testChapters,
                startTrackIndex = -5,
            )

        assertThat(state.currentTrackIndex, `is`(0))
    }

    // =====================
    // Computed Property Tests - Basic
    // =====================

    @Test
    fun `hasMedia is false when audiobook is null`() {
        val state = PlaybackState(audiobook = null, tracks = testTracks)
        assertThat(state.hasMedia, `is`(false))
    }

    @Test
    fun `hasMedia is false when tracks are empty`() {
        val state = PlaybackState(audiobook = testAudiobook, tracks = emptyList())
        assertThat(state.hasMedia, `is`(false))
    }

    @Test
    fun `hasMedia is true when both audiobook and tracks present`() {
        val state = PlaybackState(audiobook = testAudiobook, tracks = testTracks)
        assertThat(state.hasMedia, `is`(true))
    }

    @Test
    fun `currentTrack returns null when tracks are empty`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.currentTrack, `is`(nullValue()))
    }

    @Test
    fun `currentTrack returns null when index out of bounds`() {
        val state = PlaybackState(tracks = testTracks, currentTrackIndex = 99)
        assertThat(state.currentTrack, `is`(nullValue()))
    }

    @Test
    fun `currentTrack returns correct track`() {
        val state = PlaybackState(tracks = testTracks, currentTrackIndex = 1)
        assertThat(state.currentTrack, `is`(testTracks[1]))
    }

    // =====================
    // Book Position Tests
    // =====================

    @Test
    fun `bookPositionMs returns 0 when tracks are empty`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.bookPositionMs, `is`(0L))
    }

    @Test
    fun `bookPositionMs at start of first track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.bookPositionMs, `is`(0L))
    }

    @Test
    fun `bookPositionMs within first track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.bookPositionMs, `is`(30_000L))
    }

    @Test
    fun `bookPositionMs at start of second track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.bookPositionMs, `is`(60_000L))
    }

    @Test
    fun `bookPositionMs within second track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 25_000L,
            )
        assertThat(state.bookPositionMs, `is`(85_000L))
    }

    @Test
    fun `bookPositionMs at start of third track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 2,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.bookPositionMs, `is`(120_000L))
    }

    @Test
    fun `bookPositionMs at end of book`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 2,
                currentTrackPositionMs = 60_000L,
            )
        assertThat(state.bookPositionMs, `is`(180_000L))
    }

    // =====================
    // Duration Tests
    // =====================

    @Test
    fun `bookDurationMs sums all track durations`() {
        val state = PlaybackState(tracks = testTracks)
        assertThat(state.bookDurationMs, `is`(180_000L))
    }

    @Test
    fun `bookDurationMs returns 0 for empty tracks`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.bookDurationMs, `is`(0L))
    }

    @Test
    fun `currentTrackDurationMs returns correct duration`() {
        val state = PlaybackState(tracks = testTracks, currentTrackIndex = 1)
        assertThat(state.currentTrackDurationMs, `is`(60_000L))
    }

    @Test
    fun `currentTrackDurationMs returns 0 when no current track`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.currentTrackDurationMs, `is`(0L))
    }

    // =====================
    // Chapter Detection Tests
    // =====================

    @Test
    fun `currentChapter returns null when chapters are empty`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = emptyList(),
                currentTrackIndex = 1,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.currentChapter, `is`(nullValue()))
    }

    @Test
    fun `currentChapter detects first chapter at beginning`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.currentChapter, `is`(testChapters[0]))
    }

    @Test
    fun `currentChapter detects first chapter in middle`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 45_000L,
            )
        assertThat(state.currentChapter, `is`(testChapters[0]))
    }

    @Test
    fun `currentChapter detects second chapter at boundary`() {
        // Track 2, position 0s (start of chapter 2)
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.currentChapter, `is`(testChapters[1]))
    }

    @Test
    fun `currentChapter detects third chapter`() {
        // Track 2, position 45s (in chapter 3 which starts at 30s)
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 45_000L,
            )
        assertThat(state.currentChapter, `is`(testChapters[2]))
    }

    @Test
    fun `currentChapterIndex returns -1 when chapters are empty`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = emptyList(),
            )
        assertThat(state.currentChapterIndex, `is`(-1))
    }

    @Test
    fun `currentChapterIndex returns correct index for first chapter`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.currentChapterIndex, `is`(0))
    }

    @Test
    fun `currentChapterIndex returns correct index for second chapter`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 15_000L, // In Chapter 2 (track 2, 0-30s)
            )
        assertThat(state.currentChapterIndex, `is`(1))
    }

    @Test
    fun `currentChapterIndex returns correct index for third chapter`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 50_000L, // In Chapter 3 (track 2, 30-60s)
            )
        assertThat(state.currentChapterIndex, `is`(2))
    }

    // =====================
    // Chapter Duration and Position Tests
    // =====================

    @Test
    fun `currentChapterDurationMs returns 0 when no chapters`() {
        val state = PlaybackState(tracks = testTracks, chapters = emptyList())
        assertThat(state.currentChapterDurationMs, `is`(0L))
    }

    @Test
    fun `currentChapterDurationMs calculates first chapter duration`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.currentChapterDurationMs, `is`(60_000L))
    }

    @Test
    fun `currentChapterDurationMs calculates second chapter duration`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 15_000L, // In Chapter 2 (0-30s)
            )
        assertThat(state.currentChapterDurationMs, `is`(30_000L))
    }

    @Test
    fun `currentChapterDurationMs calculates last chapter duration to end of book`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 2,
                currentTrackPositionMs = 50_000L, // In Chapter 4
            )
        assertThat(state.currentChapterDurationMs, `is`(60_000L))
    }

    @Test
    fun `currentChapterPositionMs returns 0 when no chapters`() {
        val state = PlaybackState(tracks = testTracks, chapters = emptyList())
        assertThat(state.currentChapterPositionMs, `is`(0L))
    }

    @Test
    fun `currentChapterPositionMs calculates position in first chapter`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 45_000L,
            )
        assertThat(state.currentChapterPositionMs, `is`(45_000L))
    }

    @Test
    fun `currentChapterPositionMs calculates position in second chapter`() {
        // Track 2, position 10s into Chapter 2 (which starts at 0s)
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 10_000L,
            )
        assertThat(state.currentChapterPositionMs, `is`(10_000L))
    }

    @Test
    fun `currentChapterPositionMs calculates position at chapter boundary`() {
        // Track 2, position 30s - at start of Chapter 3
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.currentChapterPositionMs, `is`(0L))
    }

    // =====================
    // Progress Calculation Tests
    // =====================

    @Test
    fun `bookProgress returns 0 when duration is 0`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.bookProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `bookProgress at start of book`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.bookProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `bookProgress at 50 percent`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                // 90s out of 180s
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.bookProgress.toDouble(), `is`(closeTo(0.5, 0.001)))
    }

    @Test
    fun `bookProgress at end of book`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 2,
                currentTrackPositionMs = 60_000L,
            )
        assertThat(state.bookProgress.toDouble(), `is`(closeTo(1.0, 0.001)))
    }

    @Test
    fun `trackProgress returns 0 when no current track`() {
        val state = PlaybackState(tracks = emptyList())
        assertThat(state.trackProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `trackProgress at start of track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 0L,
            )
        assertThat(state.trackProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `trackProgress at 50 percent of track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.trackProgress.toDouble(), `is`(closeTo(0.5, 0.001)))
    }

    @Test
    fun `trackProgress at end of track`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 60_000L,
            )
        assertThat(state.trackProgress.toDouble(), `is`(closeTo(1.0, 0.001)))
    }

    @Test
    fun `chapterProgress returns 0 when no chapters`() {
        val state = PlaybackState(tracks = testTracks, chapters = emptyList())
        assertThat(state.chapterProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `chapterProgress at start of chapter`() {
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 30_000L, // Start of Chapter 3
            )
        assertThat(state.chapterProgress.toDouble(), `is`(closeTo(0.0, 0.001)))
    }

    @Test
    fun `chapterProgress at 50 percent of chapter`() {
        // Chapter 3: 30-60s (30s duration), position at 45s = 15s/30s = 50%
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 45_000L,
            )
        assertThat(state.chapterProgress.toDouble(), `is`(closeTo(0.5, 0.001)))
    }

    @Test
    fun `chapterProgress near end of chapter`() {
        // Chapter 1: 0-60s (60s duration), position at 30s = 30s/60s = 50%
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = testChapters,
                currentTrackIndex = 0,
                currentTrackPositionMs = 30_000L,
            )
        assertThat(state.chapterProgress.toDouble(), `is`(closeTo(0.5, 0.001)))
    }

    // =====================
    // State Update Method Tests
    // =====================

    @Test
    fun `withPosition creates new state with updated position`() {
        val original =
            PlaybackState(
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )

        val updated = original.withPosition(trackIndex = 1, positionMs = 25_000L)

        // Original unchanged
        assertThat(original.currentTrackIndex, `is`(0))
        assertThat(original.currentTrackPositionMs, `is`(10_000L))

        // Updated has new values
        assertThat(updated.currentTrackIndex, `is`(1))
        assertThat(updated.currentTrackPositionMs, `is`(25_000L))

        // Other properties unchanged
        assertThat(updated.tracks, `is`(original.tracks))
        assertThat(updated.isPlaying, `is`(original.isPlaying))
    }

    @Test
    fun `withPosition coerces negative position to 0`() {
        val state = PlaybackState(tracks = testTracks)
        val updated = state.withPosition(0, -100L)

        assertThat(updated.currentTrackPositionMs, `is`(0L))
    }

    @Test
    fun `withPosition coerces out-of-bounds track index`() {
        val state = PlaybackState(tracks = testTracks)
        val updated = state.withPosition(999, 0L)

        assertThat(updated.currentTrackIndex, `is`(2)) // last valid index
    }

    @Test
    fun `withPosition updates lastUpdatedAtMs`() {
        val original = PlaybackState(tracks = testTracks, lastUpdatedAtMs = 1000L)
        Thread.sleep(10) // Ensure time progresses
        val updated = original.withPosition(0, 0L)

        assertThat(updated.lastUpdatedAtMs > original.lastUpdatedAtMs, `is`(true))
    }

    @Test
    fun `withPlayingState creates new state with updated playing state`() {
        val original = PlaybackState(tracks = testTracks, isPlaying = false)
        val updated = original.withPlayingState(true)

        assertThat(original.isPlaying, `is`(false))
        assertThat(updated.isPlaying, `is`(true))
    }

    @Test
    fun `withPlayingState updates lastUpdatedAtMs`() {
        val original = PlaybackState(tracks = testTracks, lastUpdatedAtMs = 1000L)
        Thread.sleep(10)
        val updated = original.withPlayingState(true)

        assertThat(updated.lastUpdatedAtMs > original.lastUpdatedAtMs, `is`(true))
    }

    @Test
    fun `withPlaybackSpeed creates new state with updated speed`() {
        val original = PlaybackState(tracks = testTracks, playbackSpeed = 1.0f)
        val updated = original.withPlaybackSpeed(1.5f)

        assertThat(original.playbackSpeed, `is`(1.0f))
        assertThat(updated.playbackSpeed, `is`(1.5f))
    }

    @Test
    fun `withPlaybackSpeed coerces speed below minimum`() {
        val state = PlaybackState(tracks = testTracks)
        val updated = state.withPlaybackSpeed(0.1f)

        assertThat(updated.playbackSpeed, `is`(0.5f)) // minimum
    }

    @Test
    fun `withPlaybackSpeed coerces speed above maximum`() {
        val state = PlaybackState(tracks = testTracks)
        val updated = state.withPlaybackSpeed(5.0f)

        assertThat(updated.playbackSpeed, `is`(3.0f)) // maximum
    }

    @Test
    fun `withPlaybackSpeed updates lastUpdatedAtMs`() {
        val original = PlaybackState(tracks = testTracks, lastUpdatedAtMs = 1000L)
        Thread.sleep(10)
        val updated = original.withPlaybackSpeed(1.5f)

        assertThat(updated.lastUpdatedAtMs > original.lastUpdatedAtMs, `is`(true))
    }

    // =====================
    // hasSignificantPositionChange Tests
    // =====================

    @Test
    fun `hasSignificantPositionChange detects different audiobooks`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 =
            PlaybackState(
                audiobook = testAudiobook.copy(id = "plex:999"),
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )

        assertThat(state1.hasSignificantPositionChange(state2), `is`(true))
    }

    @Test
    fun `hasSignificantPositionChange detects different track index`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackIndex = 1)

        assertThat(state1.hasSignificantPositionChange(state2), `is`(true))
    }

    @Test
    fun `hasSignificantPositionChange detects position change above threshold`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackPositionMs = 12_000L)

        assertThat(state1.hasSignificantPositionChange(state2, thresholdMs = 1000L), `is`(true))
    }

    @Test
    fun `hasSignificantPositionChange ignores position change below threshold`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackPositionMs = 10_500L)

        assertThat(state1.hasSignificantPositionChange(state2, thresholdMs = 1000L), `is`(false))
    }

    @Test
    fun `hasSignificantPositionChange detects negative position change above threshold`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackPositionMs = 8_000L)

        assertThat(state1.hasSignificantPositionChange(state2, thresholdMs = 1000L), `is`(true))
    }

    @Test
    fun `hasSignificantPositionChange uses default threshold of 1000ms`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackPositionMs = 10_999L)

        // 999ms change - below default threshold
        assertThat(state1.hasSignificantPositionChange(state2), `is`(false))
    }

    @Test
    fun `hasSignificantPositionChange at exact threshold boundary`() {
        val state1 =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 0,
                currentTrackPositionMs = 10_000L,
            )
        val state2 = state1.copy(currentTrackPositionMs = 11_000L)

        // Exactly 1000ms change - should be considered significant
        assertThat(state1.hasSignificantPositionChange(state2, thresholdMs = 1000L), `is`(true))
    }

    // =====================
    // Edge Cases Tests
    // =====================

    @Test
    fun `state with empty tracks handles all computed properties gracefully`() {
        val state =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = emptyList(),
                chapters = emptyList(),
            )

        assertThat(state.hasMedia, `is`(false))
        assertThat(state.currentTrack, `is`(nullValue()))
        assertThat(state.currentChapter, `is`(nullValue()))
        assertThat(state.currentChapterIndex, `is`(-1))
        assertThat(state.bookPositionMs, `is`(0L))
        assertThat(state.bookDurationMs, `is`(0L))
        assertThat(state.currentTrackDurationMs, `is`(0L))
        assertThat(state.currentChapterDurationMs, `is`(0L))
        assertThat(state.currentChapterPositionMs, `is`(0L))
        assertThat(state.bookProgress, `is`(0f))
        assertThat(state.trackProgress, `is`(0f))
        assertThat(state.chapterProgress, `is`(0f))
    }

    @Test
    fun `state with null audiobook and empty tracks matches EMPTY`() {
        val state = PlaybackState(audiobook = null, tracks = emptyList())

        assertThat(state.hasMedia, `is`(PlaybackState.EMPTY.hasMedia))
        assertThat(state.currentTrack, `is`(PlaybackState.EMPTY.currentTrack))
        assertThat(state.bookPositionMs, `is`(PlaybackState.EMPTY.bookPositionMs))
    }

    @Test
    fun `toString includes relevant information`() {
        val state =
            PlaybackState(
                audiobook = testAudiobook,
                tracks = testTracks,
                currentTrackIndex = 1,
                currentTrackPositionMs = 30_000L,
                isPlaying = true,
            )

        val str = state.toString()
        assertThat(str.contains("Test Audiobook"), `is`(true))
        assertThat(str.contains("track=1"), `is`(true))
        assertThat(str.contains("pos=30000ms"), `is`(true))
        assertThat(str.contains("playing=true"), `is`(true))
    }

    @Test
    fun `toString with null audiobook shows None`() {
        val state = PlaybackState.EMPTY
        val str = state.toString()
        assertThat(str.contains("None"), `is`(true))
    }

    // =====================
    // Chapter Transition Marker Tests
    // =====================

    /**
     * Test that chapter detection filters out very short chapters (transition markers).
     * This reproduces the bug where "02:33/00:00" is displayed because the transition marker
     * with ~44ms duration is selected instead of the content chapter with meaningful duration.
     *
     * Real-world scenario from logs:
     * - Content chapter: Chapter 1, duration=1054550ms (~17.5 minutes)
     * - Transition marker: Chapter 2, duration=50ms (at same start position)
     * When playback is at 5138ms, we should select the long chapter, not the 50ms marker.
     */
    @Test
    fun `currentChapter prefers content chapters over transition markers`() {
        // Create a realistic scenario based on logs/miscalc.log where both chapters
        // start at position 0 (one is content, one is a very short transition marker)
        val chaptersWithTransitionMarkers =
            listOf(
                // Content chapter - Chapter 1: 0 to 30 seconds (meaningful duration)
                Chapter(
                    id = 1,
                    title = "Chapter 1",
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 30_000L,
                    trackId = "plex:1",
                    bookId = "plex:100",
                ),
                // Transition marker - very short (~50ms) at same start position
                Chapter(
                    id = 2,
                    title = "Chapter 1 Marker",
                    index = 2,
                    startTimeOffset = 0L,
                    endTimeOffset = 50L,
                    trackId = "plex:1",
                    bookId = "plex:100",
                ),
            )

        // Position 5138ms is within the range of both chapters, but only one has meaningful duration
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = chaptersWithTransitionMarkers,
                currentTrackIndex = 0,
                currentTrackPositionMs = 5_138L,
            )

        // Should select the content chapter with meaningful duration, NOT the <1s transition marker
        val selectedChapter = state.currentChapter
        assertThat("Expected content chapter to be selected", selectedChapter, `is`(chaptersWithTransitionMarkers[0]))
        assertThat("Expected chapter with long duration", selectedChapter?.title, `is`("Chapter 1"))

        // Verify the chapter has meaningful duration (not ~50ms like transition markers)
        val duration = state.currentChapterDurationMs
        assertThat("Expected content chapter duration (30s), not transition marker (<1s)", duration >= 30_000L, `is`(true))
    }

    @Test
    fun `currentChapter handles position at transition marker boundary`() {
        // Create tracks with longer duration to match chapter offsets
        val longTracks =
            listOf(
                MediaItemTrack(id = "plex:65798", libraryId = "plex:lib:1", title = "Track 1", duration = 1_100_000L, index = 1),
            )
        
        val chaptersWithTransitionMarkers =
            listOf(
                // Content chapter (index=1) - track-relative
                Chapter(
                    id = 4041,
                    title = "Chapter 1",
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 1_054_550L,
                    trackId = "plex:65798",
                    bookId = "plex:65797",
                ),
                // Transition marker (index=2) at exact end of Chapter 1 - track-relative
                Chapter(
                    id = 4042,
                    title = "Chapter 2",
                    index = 2,
                    startTimeOffset = 1_054_550L,
                    endTimeOffset = 1_054_600L,
                    trackId = "plex:65798",
                    bookId = "plex:65797",
                ),
            )

        // Position exactly at transition marker start - should still prefer content chapter
        val state =
            PlaybackState(
                tracks = longTracks,
                chapters = chaptersWithTransitionMarkers,
                currentTrackIndex = 0,
                currentTrackPositionMs = 1_054_550L,
            )

        // At the boundary between chapters: position 1_054_550L is >= Chapter 1 end
        // So we're technically in the transition marker, but it's filtered out
        // The fallback should select the last content chapter
        val selectedChapter = state.currentChapter
        assertThat("Expected content chapter, not transition marker", selectedChapter?.index, `is`(1L))
    }

    @Test
    fun `currentChapter falls back to any chapter when no content chapters exist`() {
        // Create tracks with longer duration
        val longTracks =
            listOf(
                MediaItemTrack(id = "plex:65798", libraryId = "plex:lib:1", title = "Track 1", duration = 1_100_000L, index = 1),
            )
        
        // Edge case: only transition markers available (shouldn't happen in practice)
        val onlyTransitionMarkers =
            listOf(
                Chapter(
                    id = 4041,
                    title = "Chapter 2",
                    index = 2,
                    startTimeOffset = 1_054_550L,
                    endTimeOffset = 1_054_600L,
                    trackId = "plex:65798",
                    bookId = "plex:65797",
                ),
            )

        val state =
            PlaybackState(
                tracks = longTracks,
                chapters = onlyTransitionMarkers,
                currentTrackIndex = 0,
                currentTrackPositionMs = 1_054_550L,
            )

        // Should still return something rather than null for backward compatibility
        val selectedChapter = state.currentChapter
        assertThat("Should return a chapter even if only transition markers exist", selectedChapter, `is`(onlyTransitionMarkers[0]))
    }

    // =====================
    // Multi-Track Chapter Detection Tests (Track-Relative Offsets)
    // =====================

    /**
     * Tests chapter detection with TRACK-RELATIVE offsets (real-world scenario).
     * This reproduces the bug where comparing book-relative position against
     * track-relative chapter offsets causes the wrong chapter to be selected.
     *
     * Setup:
     * - Track 1 (60s): Chapter 1 (0-40s), Chapter 2 (40-60s)
     * - Track 2 (60s): Chapter 3 (0-30s), Chapter 4 (30-60s)
     * - Track 3 (60s): Chapter 5 (0-60s)
     *
     * Bug scenario: When playing Track 2 at position 15s:
     * - bookPositionMs = 60000 + 15000 = 75000ms
     * - currentTrackPositionMs = 15000ms
     * - All chapters match (75000 >= all startTimeOffsets which are track-relative 0-60s)
     * - lastOrNull returns Chapter 5 (the last chapter) instead of Chapter 3
     */
    @Test
    fun `currentChapter with track-relative offsets selects correct chapter from current track`() {
        // Track-relative chapters (real-world scenario)
        val trackRelativeChapters =
            listOf(
                // Track 1 chapters
                Chapter(
                    id = 1,
                    title = "Chapter 1",
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 40_000L,
                    trackId = "plex:1",
                    bookId = "plex:100",
                ),
                Chapter(
                    id = 2,
                    title = "Chapter 2",
                    index = 2,
                    startTimeOffset = 40_000L,
                    endTimeOffset = 60_000L,
                    trackId = "plex:1",
                    bookId = "plex:100",
                ),
                // Track 2 chapters
                Chapter(
                    id = 3,
                    title = "Chapter 3",
                    index = 3,
                    startTimeOffset = 0L,
                    endTimeOffset = 30_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
                Chapter(
                    id = 4,
                    title = "Chapter 4",
                    index = 4,
                    startTimeOffset = 30_000L,
                    endTimeOffset = 60_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
                // Track 3 chapters
                Chapter(
                    id = 5,
                    title = "Chapter 5",
                    index = 5,
                    startTimeOffset = 0L,
                    endTimeOffset = 60_000L,
                    trackId = "plex:3",
                    bookId = "plex:100",
                ),
            )

        // Playing Track 2 (index=1) at 15 seconds
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = trackRelativeChapters,
                currentTrackIndex = 1, // Track 2
                currentTrackPositionMs = 15_000L, // 15s into track 2
            )

        // Should select Chapter 3 (from track 2, which contains position 15s)
        val selectedChapter = state.currentChapter
        assertThat("Expected Chapter 3 from current track", selectedChapter?.title, `is`("Chapter 3"))
        assertThat("Expected Chapter 3", selectedChapter?.id, `is`(3L))
    }

    @Test
    fun `currentChapter with track-relative offsets at track boundary`() {
        val trackRelativeChapters =
            listOf(
                Chapter(
                    id = 1,
                    title = "Chapter 1",
                    index = 1,
                    startTimeOffset = 40_000L,
                    endTimeOffset = 60_000L,
                    trackId = "plex:1",
                    bookId = "plex:100",
                ),
                Chapter(
                    id = 2,
                    title = "Chapter 2",
                    index = 2,
                    startTimeOffset = 0L,
                    endTimeOffset = 30_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
            )

        // At start of Track 2 (index=1)
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = trackRelativeChapters,
                currentTrackIndex = 1, // Track 2
                currentTrackPositionMs = 0L, // Start of track 2
            )

        // Should select Chapter 2 (from track 2, position 0)
        val selectedChapter = state.currentChapter
        assertThat("Expected Chapter 2 at start of track 2", selectedChapter?.title, `is`("Chapter 2"))
    }

    @Test
    fun `currentChapter with track-relative offsets in middle of second chapter`() {
        val trackRelativeChapters =
            listOf(
                Chapter(
                    id = 1,
                    title = "Chapter 1",
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 30_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
                Chapter(
                    id = 2,
                    title = "Chapter 2",
                    index = 2,
                    startTimeOffset = 30_000L,
                    endTimeOffset = 60_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
            )

        // Playing Track 2 at 45 seconds (in Chapter 2)
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = trackRelativeChapters,
                currentTrackIndex = 1, // Track 2
                currentTrackPositionMs = 45_000L,
            )

        val selectedChapter = state.currentChapter
        assertThat("Expected Chapter 2", selectedChapter?.title, `is`("Chapter 2"))
    }

    @Test
    fun `currentChapterIndex with track-relative offsets returns correct index`() {
        val trackRelativeChapters =
            listOf(
                Chapter(
                    id = 1,
                    title = "Chapter 1",
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 60_000L,
                    trackId = "plex:1",
                    bookId = "plex:100",
                ),
                Chapter(
                    id = 2,
                    title = "Chapter 2",
                    index = 2,
                    startTimeOffset = 0L,
                    endTimeOffset = 30_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
                Chapter(
                    id = 3,
                    title = "Chapter 3",
                    index = 3,
                    startTimeOffset = 30_000L,
                    endTimeOffset = 60_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
            )

        // Playing Track 2, Chapter 3
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = trackRelativeChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 45_000L,
            )

        assertThat("Expected chapter index 2 (0-based)", state.currentChapterIndex, `is`(2))
    }

    @Test
    fun `currentChapterDurationMs with track-relative offsets calculates correctly`() {
        val trackRelativeChapters =
            listOf(
                Chapter(
                    id = 1,
                    title = "Chapter 1",
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 60_000L,
                    trackId = "plex:1",
                    bookId = "plex:100",
                ),
                Chapter(
                    id = 2,
                    title = "Chapter 2",
                    index = 2,
                    startTimeOffset = 0L,
                    endTimeOffset = 30_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
                Chapter(
                    id = 3,
                    title = "Chapter 3",
                    index = 3,
                    startTimeOffset = 30_000L,
                    endTimeOffset = 60_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
            )

        // Playing Track 2, Chapter 2 (duration should be 30s)
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = trackRelativeChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 15_000L,
            )

        assertThat("Expected Chapter 2 duration of 30s", state.currentChapterDurationMs, `is`(30_000L))
    }

    @Test
    fun `currentChapterPositionMs with track-relative offsets calculates correctly`() {
        val trackRelativeChapters =
            listOf(
                Chapter(
                    id = 1,
                    title = "Chapter 1",
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 30_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
                Chapter(
                    id = 2,
                    title = "Chapter 2",
                    index = 2,
                    startTimeOffset = 30_000L,
                    endTimeOffset = 60_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
            )

        // Playing Track 2 at 45s (15s into Chapter 2)
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = trackRelativeChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 45_000L,
            )

        assertThat("Expected 15s into Chapter 2", state.currentChapterPositionMs, `is`(15_000L))
    }

    @Test
    fun `currentChapter with track-relative offsets and transition markers prefers content chapters`() {
        val trackRelativeChapters =
            listOf(
                // Content chapter on track 2
                Chapter(
                    id = 1,
                    title = "Chapter 1",
                    index = 1,
                    startTimeOffset = 0L,
                    endTimeOffset = 30_000L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
                // Transition marker on track 2
                Chapter(
                    id = 2,
                    title = "Chapter 1 Marker",
                    index = 2,
                    startTimeOffset = 0L,
                    endTimeOffset = 50L,
                    trackId = "plex:2",
                    bookId = "plex:100",
                ),
            )

        // Playing Track 2 at 5s
        val state =
            PlaybackState(
                tracks = testTracks,
                chapters = trackRelativeChapters,
                currentTrackIndex = 1,
                currentTrackPositionMs = 5_000L,
            )

        // Should prefer content chapter over transition marker
        val selectedChapter = state.currentChapter
        assertThat("Expected content chapter", selectedChapter?.title, `is`("Chapter 1"))
        assertThat("Expected meaningful duration", state.currentChapterDurationMs >= 30_000L, `is`(true))
    }
}
