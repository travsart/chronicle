package local.oss.chronicle.features.player

import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.Chapter
import local.oss.chronicle.data.model.MediaItemTrack

/**
 * Immutable representation of the current playback state.
 * This is the single source of truth for playback state throughout the app.
 *
 * All position values are in milliseconds.
 *
 * Design Principles:
 * 1. ExoPlayer's position is authoritative
 * 2. All state is immutable - use copy() for updates
 * 3. Derived properties are computed, not stored
 *
 * @property audiobook The currently playing audiobook, or null if none
 * @property tracks List of all tracks in the audiobook
 * @property chapters List of all chapters in the audiobook
 * @property currentTrackIndex Index of the currently playing track (0-based)
 * @property currentTrackPositionMs Position within the current track in milliseconds
 * @property isPlaying Whether playback is currently active
 * @property playbackSpeed Current playback speed multiplier (1.0 = normal)
 * @property lastUpdatedAtMs Timestamp when this state was last updated
 */
data class PlaybackState(
    val audiobook: Audiobook? = null,
    val tracks: List<MediaItemTrack> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val currentTrackIndex: Int = 0,
    val currentTrackPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val lastUpdatedAtMs: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * Empty playback state representing no media loaded.
         */
        val EMPTY = PlaybackState()

        /**
         * Creates a PlaybackState from audiobook data.
         * Used when starting playback of a new audiobook.
         */
        fun fromAudiobook(
            audiobook: Audiobook,
            tracks: List<MediaItemTrack>,
            chapters: List<Chapter>,
            startTrackIndex: Int = 0,
            startPositionMs: Long = 0L,
        ): PlaybackState =
            PlaybackState(
                audiobook = audiobook,
                tracks = tracks,
                chapters = chapters,
                currentTrackIndex = startTrackIndex.coerceIn(0, tracks.lastIndex.coerceAtLeast(0)),
                currentTrackPositionMs = startPositionMs.coerceAtLeast(0L),
                isPlaying = false,
                playbackSpeed = 1.0f,
            )
    }

    // =====================
    // Computed Properties
    // =====================

    /**
     * Whether any media is loaded for playback.
     */
    val hasMedia: Boolean
        get() = audiobook != null && tracks.isNotEmpty()

    /**
     * The currently playing track, or null if no media loaded or index is invalid.
     */
    val currentTrack: MediaItemTrack?
        get() = tracks.getOrNull(currentTrackIndex)

    /**
     * The current chapter based on track-relative position, or null if no chapters or position is invalid.
     *
     * Chapters use track-relative offsets (position within the containing track), not book-relative.
     * This method finds the chapter from the current track that contains the current track position.
     *
     * Plex chapter data includes both:
     * - Content chapters (index=1): The actual chapter content with meaningful duration
     * - Transition markers (index=2): Short markers (~44ms) at chapter boundaries
     *
     * This property filters out very short chapters (transition markers) to avoid
     * displaying "00:00" for chapter duration when a transition marker is incorrectly selected.
     */
    val currentChapter: Chapter?
        get() {
            if (chapters.isEmpty()) return null
            val trackId = currentTrack?.id ?: return null
            val trackPos = currentTrackPositionMs

            // Filter chapters to current track only
            val trackChapters = chapters.filter { it.trackId == trackId }
            if (trackChapters.isEmpty()) return null

            // Filter out very short chapters (< 1 second) which are likely transition markers
            // Transition markers are typically 40-50ms, while real chapters are much longer
            val meaningfulChapters =
                trackChapters.filter { (it.endTimeOffset - it.startTimeOffset) >= 1000L }
            val chaptersToSearch = meaningfulChapters.ifEmpty { trackChapters }

            // Find the chapter that contains the current track position
            return chaptersToSearch.firstOrNull {
                trackPos >= it.startTimeOffset && trackPos < it.endTimeOffset
            } ?: chaptersToSearch.lastOrNull { it.startTimeOffset <= trackPos }
                ?: chaptersToSearch.firstOrNull()
        }

    /**
     * Index of the current chapter (0-based) in the full chapters list, or -1 if no chapter found.
     * Uses track-aware lookup to find the chapter that contains the current position.
     */
    val currentChapterIndex: Int
        get() {
            if (chapters.isEmpty()) return -1
            val chapter = currentChapter ?: return -1
            // Find the index by matching id, trackId, and startTimeOffset for uniqueness
            return chapters.indexOfFirst {
                it.id == chapter.id &&
                    it.trackId == chapter.trackId &&
                    it.startTimeOffset == chapter.startTimeOffset
            }.takeIf { it >= 0 } ?: 0
        }

    /**
     * Position within the entire audiobook in milliseconds.
     * Calculated as sum of previous track durations + current track position.
     */
    val bookPositionMs: Long
        get() {
            if (tracks.isEmpty()) return 0L
            var position = 0L
            for (i in 0 until currentTrackIndex) {
                position += tracks.getOrNull(i)?.duration ?: 0L
            }
            return position + currentTrackPositionMs
        }

    /**
     * Total duration of the audiobook in milliseconds.
     */
    val bookDurationMs: Long
        get() = tracks.sumOf { it.duration }

    /**
     * Duration of the current track in milliseconds.
     */
    val currentTrackDurationMs: Long
        get() = currentTrack?.duration ?: 0L

    /**
     * Duration of the current chapter in milliseconds.
     * Calculates based on track-relative chapter bounds.
     */
    val currentChapterDurationMs: Long
        get() {
            val chapter = currentChapter ?: return 0L
            return (chapter.endTimeOffset - chapter.startTimeOffset).coerceAtLeast(0L)
        }

    /**
     * Position within the current chapter in milliseconds (track-relative).
     */
    val currentChapterPositionMs: Long
        get() {
            val chapter = currentChapter ?: return 0L
            return (currentTrackPositionMs - chapter.startTimeOffset).coerceAtLeast(0L)
        }

    /**
     * Progress through the book as a fraction (0.0 to 1.0).
     */
    val bookProgress: Float
        get() {
            val duration = bookDurationMs
            return if (duration > 0) {
                (bookPositionMs.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                0f
            }
        }

    /**
     * Progress through the current track as a fraction (0.0 to 1.0).
     */
    val trackProgress: Float
        get() {
            val duration = currentTrackDurationMs
            return if (duration > 0) {
                (currentTrackPositionMs.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                0f
            }
        }

    /**
     * Progress through the current chapter as a fraction (0.0 to 1.0).
     */
    val chapterProgress: Float
        get() {
            val duration = currentChapterDurationMs
            return if (duration > 0) {
                (currentChapterPositionMs.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                0f
            }
        }

    // =====================
    // State Update Methods
    // =====================

    /**
     * Creates a copy with updated position.
     */
    fun withPosition(
        trackIndex: Int,
        positionMs: Long,
    ): PlaybackState =
        copy(
            currentTrackIndex = trackIndex.coerceIn(0, tracks.lastIndex.coerceAtLeast(0)),
            currentTrackPositionMs = positionMs.coerceAtLeast(0L),
            lastUpdatedAtMs = System.currentTimeMillis(),
        )

    /**
     * Creates a copy with updated playback state.
     */
    fun withPlayingState(isPlaying: Boolean): PlaybackState =
        copy(
            isPlaying = isPlaying,
            lastUpdatedAtMs = System.currentTimeMillis(),
        )

    /**
     * Creates a copy with updated playback speed.
     */
    fun withPlaybackSpeed(speed: Float): PlaybackState =
        copy(
            playbackSpeed = speed.coerceIn(0.5f, 3.0f),
            lastUpdatedAtMs = System.currentTimeMillis(),
        )

    // =====================
    // Utility Methods
    // =====================

    /**
     * Returns whether the position has changed significantly from another state.
     * Used to determine if state should be persisted to database.
     *
     * @param other The previous state to compare against
     * @param thresholdMs Minimum position change to be considered significant
     */
    fun hasSignificantPositionChange(
        other: PlaybackState,
        thresholdMs: Long = 1000L,
    ): Boolean {
        if (audiobook?.key != other.audiobook?.key) return true
        if (currentTrackIndex != other.currentTrackIndex) return true
        return kotlin.math.abs(currentTrackPositionMs - other.currentTrackPositionMs) >= thresholdMs
    }

    override fun toString(): String {
        val bookTitle = audiobook?.title?.take(20) ?: "None"
        return "PlaybackState(book=$bookTitle, track=$currentTrackIndex, " +
            "pos=${currentTrackPositionMs}ms, playing=$isPlaying)"
    }
}

/**
 * Extension property to get the unique key for an audiobook.
 * For Plex: uses the rating key (id)
 */
private val Audiobook.key: String
    get() = id.toString()
