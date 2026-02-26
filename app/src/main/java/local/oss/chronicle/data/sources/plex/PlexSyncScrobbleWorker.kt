package local.oss.chronicle.data.sources.plex

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import local.oss.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import timber.log.Timber

/**
 * Reports playback progress to Plex server using library-aware connections.
 *
 * Migrated from Worker to CoroutineWorker to properly handle suspend functions
 * and return Result only after async work completes.
 *
 * This worker eliminates the race condition in multi-library setups by using
 * [PlexProgressReporter] which creates request-scoped Retrofit instances instead
 * of mutating global [PlexConfig.url].
 *
 * @see PlexProgressReporter
 * @see docs/architecture/progress-reporting-overhaul.md
 */
class PlexSyncScrobbleWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    // Inject dependencies via Injector.get() (WorkManager doesn't support constructor injection easily)
    private val trackRepository = Injector.get().trackRepo()
    private val bookRepository = Injector.get().bookRepo()
    private val serverConnectionResolver = Injector.get().serverConnectionResolver()
    private val progressReporter = Injector.get().progressReporter()
    private val plexPrefs = Injector.get().plexPrefs()

    /**
     * Executes progress reporting with proper async handling.
     * Returns only after all API calls complete or fail.
     *
     * @return Result.success() if progress was reported successfully
     *         Result.retry() if a transient error occurred (network timeout, etc.)
     *         Result.failure() if a permanent error occurred (auth failure, etc.)
     */
    override suspend fun doWork(): Result {
        // Extract input data
        val trackId = inputData.requireString(TRACK_ID_ARG)
        val playbackState = inputData.requireString(TRACK_STATE_ARG)
        val trackProgress = inputData.requireLong(TRACK_POSITION_ARG)
        val bookProgress = inputData.requireLong(BOOK_PROGRESS_ARG)

        // Fetch track and book data
        val track = trackRepository.getTrackAsync(trackId)
        if (track == null) {
            Timber.e("Track not found: $trackId")
            return Result.failure()
        }

        val bookId = track.parentKey
        if (bookId == NO_AUDIOBOOK_FOUND_ID) {
            Timber.e("No audiobook found for track: $trackId")
            return Result.failure()
        }

        val book = bookRepository.getAudiobookAsync(bookId)
        if (book == null) {
            Timber.e("Book not found: $bookId")
            return Result.failure()
        }

        val tracks = trackRepository.getTracksForAudiobookAsync(bookId)

        // Resolve library-specific server connection
        val connection =
            try {
                serverConnectionResolver.resolve(book.libraryId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to resolve server for library: ${book.libraryId}")
                return Result.retry() // Retry on transient errors
            }

        Timber.d(
            "Reporting progress for book: ${book.title} in library: ${book.libraryId} " +
                "to server: ${connection.serverUrl}, state: $playbackState",
        )

        // Report progress using library-specific connection
        return try {
            progressReporter.reportProgress(
                connection = connection,
                track = track,
                book = book,
                tracks = tracks,
                trackProgress = trackProgress,
                bookProgress = bookProgress,
                playbackState = playbackState,
            )

            Result.success() // Only returns after API call completes
        } catch (e: Exception) {
            Timber.e(e, "Failed to report progress")
            Result.retry() // WorkManager will retry with backoff
        }
    }

    companion object {
        const val TRACK_ID_ARG = "Track ID"
        const val TRACK_STATE_ARG = "State"
        const val TRACK_POSITION_ARG = "Track position"
        const val BOOK_PROGRESS_ARG = "Book progress"
        const val LIBRARY_ID_ARG = "Library ID"

        /**
         * Creates input data for the worker.
         *
         * @param trackId The track ID (e.g., "plex:12345")
         * @param playbackState Plex state: "playing", "paused", or "stopped"
         * @param trackProgress Current position in track (ms)
         * @param bookProgress Current position in book (ms)
         * @param libraryId The library ID (e.g., "plex:library:1")
         * @return Data for WorkManager
         */
        fun makeWorkerData(
            trackId: String,
            playbackState: String,
            trackProgress: Long,
            bookProgress: Long,
            libraryId: String,
        ): Data {
            require(trackId != TRACK_NOT_FOUND) { "Invalid track ID: $trackId" }
            require(libraryId.isNotEmpty()) { "libraryId cannot be empty" }
            return workDataOf(
                TRACK_ID_ARG to trackId,
                TRACK_POSITION_ARG to trackProgress,
                TRACK_STATE_ARG to playbackState,
                BOOK_PROGRESS_ARG to bookProgress,
                LIBRARY_ID_ARG to libraryId,
            )
        }
    }

    private fun Data.requireInt(key: String): Int {
        val value = getInt(key, Int.MIN_VALUE)
        require(value != Int.MIN_VALUE) { "Missing required Int key: $key" }
        return value
    }

    private fun Data.requireLong(key: String): Long {
        val value = getLong(key, Long.MIN_VALUE)
        require(value != Long.MIN_VALUE) { "Missing required Long key: $key" }
        return value
    }

    private fun Data.requireString(key: String): String {
        val value = getString(key)
        require(!value.isNullOrEmpty()) { "Missing required String key: $key" }
        return value
    }
}
