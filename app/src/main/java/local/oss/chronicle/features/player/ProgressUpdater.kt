package local.oss.chronicle.features.player

import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.*
import local.oss.chronicle.data.sources.plex.PlexSyncScrobbleWorker
import local.oss.chronicle.data.sources.plex.model.getDuration
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import local.oss.chronicle.features.player.ProgressUpdater.Companion.BOOK_FINISHED_END_OFFSET_MILLIS
import local.oss.chronicle.features.player.ProgressUpdater.Companion.NETWORK_CALL_FREQUENCY
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * Responsible for updating playback progress of the current book/track to the local DB and to the
 * server at regular intervals while a [MediaControllerCompat] indicates that playback is active
 */
interface ProgressUpdater {
    /** Begin regularly updating the local DB and remote servers while playback is active */
    fun startRegularProgressUpdates()

    /**
     * Updates local DBs to reflect the track/book progress passed in via [progress] for a track
     * with id [trackId] and a book containing that track.
     *
     * Updates book/track progress in remote DB if [forceNetworkUpdate] == true or every
     * [NETWORK_CALL_FREQUENCY] calls. Pass additional [playbackState] for [PlexSyncScrobbleWorker]
     * to pass playback state to server
     */
    fun updateProgress(
        trackId: String,
        playbackState: String,
        progress: Long,
        forceNetworkUpdate: Boolean,
    )

    /** Update progress without providing any parameters */
    fun updateProgressWithoutParameters()

    /** Cancels regular progress updates */
    fun cancel()

    companion object {
        val BOOK_FINISHED_END_OFFSET_MILLIS = 2.minutes.inWholeMilliseconds

        /**
         * The frequency which the remote server is updated at: once for every [NETWORK_CALL_FREQUENCY]
         * calls to the local database
         *
         * Set to 30 to send progress every 30 seconds (30 ticks × 1 second interval)
         * for better Plex dashboard activity reporting.
         */
        const val NETWORK_CALL_FREQUENCY = 30
    }
}

class SimpleProgressUpdater
    @Inject
    constructor(
        private val serviceScope: CoroutineScope,
        private val trackRepository: ITrackRepository,
        private val bookRepository: IBookRepository,
        private val workManager: WorkManager,
        private val prefsRepo: PrefsRepo,
        private val currentlyPlaying: CurrentlyPlaying,
        private val playbackStateController: PlaybackStateController,
    ) : ProgressUpdater {
        var mediaController: MediaControllerCompat? = null

        /**
         * Frequency of progress updates.
         * Set to 1 second for responsive UI updates. Combined with NETWORK_CALL_FREQUENCY=30,
         * this sends progress to Plex every 30 seconds.
         */
        private val updateProgressFrequencyMs = 1000L

        /** Tracks the number of times [updateLocalProgress] has been called this session */
        private var tickCounter = 0L

        private val handler = Handler(Looper.getMainLooper())
        private val updateProgressAction = { startRegularProgressUpdates() }

        /**
         * Updates the current track/audiobook progress in the local db and remote server.
         *
         * If we are within 2 minutes of the end of the book and playback stops, mark the book as
         * "finished" by updating the first track to progress=0 and setting it as the most recently viewed
         */
        override fun startRegularProgressUpdates() {
            requireNotNull(mediaController).let { controller ->
                // Use the absolute track position from extras as baseline
                val absolutePositionFromExtras = controller.playbackState?.extras?.getLong(MediaPlayerService.EXTRA_ABSOLUTE_TRACK_POSITION) ?: 0L

                // Get auto-calculated chapter-relative position
                val chapterRelativePosition = controller.playbackState?.currentPlayBackPosition ?: 0L

                // Get chapter info to convert chapter-relative to absolute
                val chapter = currentlyPlaying.chapter.value

                // Calculate absolute position: use chapter-relative auto-calculated position + chapter start
                // Fall back to extras if no valid chapter
                val playerPosition =
                    if (chapter != EMPTY_CHAPTER && chapterRelativePosition >= 0) {
                        chapter.startTimeOffset + chapterRelativePosition
                    } else {
                        // No chapter or invalid chapter-relative position: use absolute from extras
                        absolutePositionFromExtras
                    }
                val isPlaying = controller.playbackState?.isPlaying != false
                val playbackState = controller.playbackState?.state

                // [ChapterDebug] Log the raw player state before updating progress
                Timber.d(
                    "[ChapterDebug] startRegularProgressUpdates: " +
                        "playerPosition=$playerPosition, " +
                        "isPlaying=$isPlaying, " +
                        "playbackState=$playbackState, " +
                        "trackId=${controller.metadata?.id}",
                )

                if (isPlaying) {
                    serviceScope.launch(context = serviceScope.coroutineContext + Dispatchers.IO) {
                        updateProgress(
                            controller.metadata?.id ?: TRACK_NOT_FOUND,
                            MediaPlayerService.PLEX_STATE_PLAYING,
                            playerPosition,
                            false,
                        )
                    }
                }
            }
            handler.postDelayed(updateProgressAction, updateProgressFrequencyMs)
        }

        override fun updateProgressWithoutParameters() {
            val controller = mediaController ?: return
            val playbackState =
                when (controller.playbackState.state) {
                    PlaybackStateCompat.STATE_PLAYING -> MediaPlayerService.PLEX_STATE_PLAYING
                    PlaybackStateCompat.STATE_PAUSED -> MediaPlayerService.PLEX_STATE_PAUSED
                    PlaybackStateCompat.STATE_STOPPED -> MediaPlayerService.PLEX_STATE_PAUSED
                    else -> ""
                }
            val currentTrack = controller.metadata.id ?: return

            // Use the absolute track position from extras as baseline
            val absolutePositionFromExtras = controller.playbackState?.extras?.getLong(MediaPlayerService.EXTRA_ABSOLUTE_TRACK_POSITION) ?: 0L

            // Get auto-calculated chapter-relative position
            val chapterRelativePosition = controller.playbackState?.currentPlayBackPosition ?: 0L

            // Get chapter info to convert chapter-relative to absolute
            val chapter = currentlyPlaying.chapter.value

            // Calculate absolute position: use chapter-relative auto-calculated position + chapter start
            // Fall back to extras if no valid chapter
            val playerPosition =
                if (chapter != EMPTY_CHAPTER && chapterRelativePosition >= 0) {
                    chapter.startTimeOffset + chapterRelativePosition
                } else {
                    // No chapter or invalid chapter-relative position: use absolute from extras
                    absolutePositionFromExtras
                }
            updateProgress(
                currentTrack,
                playbackState,
                playerPosition,
                false,
            )
        }

        override fun updateProgress(
            trackId: String,
            playbackState: String,
            progress: Long,
            forceNetworkUpdate: Boolean,
        ) {
            Timber.i("Updating progress")
            if (trackId == TRACK_NOT_FOUND) {
                return
            }
            val currentTime = System.currentTimeMillis()
            serviceScope.launch(context = serviceScope.coroutineContext + Dispatchers.IO) {
                // Read from PlaybackStateController to avoid race condition when switching libraries
                val state = playbackStateController.state.value
                val book = state.audiobook
                val stateCurrentTrack = state.currentTrack
                val tracks = state.tracks

                // Validate that we're updating the correct track (edge case: rapid track switching)
                // If there's a mismatch, check if it's a legitimate track transition within the same audiobook
                val trackIndex = tracks.indexOfFirst { it.id == trackId }

                if (stateCurrentTrack == null || stateCurrentTrack.id != trackId) {
                    if (trackIndex == -1) {
                        // Track not found in current audiobook - this is likely a library switch or different audiobook
                        Timber.w(
                            "Track mismatch in updateProgress: requested=$trackId, " +
                                "current=${stateCurrentTrack?.id}, not found in track list. Skipping update to avoid race condition.",
                        )
                        return@launch
                    } else {
                        // Track found in track list - this is a legitimate track transition (e.g., auto-advance to next track)
                        Timber.i(
                            "Track transition detected in updateProgress: from ${stateCurrentTrack?.id} to $trackId " +
                                "(new track index=$trackIndex). Proceeding with update.",
                        )
                    }
                }

                // No reason to update if the book doesn't exist
                if (book == null) {
                    Timber.w("No book in PlaybackState for track $trackId, skipping update")
                    return@launch
                }

                // Get the actual track we're updating (either from state or from track list if transitioning)
                val track = if (trackIndex >= 0) tracks[trackIndex] else null

                if (track == null) {
                    Timber.w("Track $trackId not found in track list, skipping update")
                    return@launch
                }

                val bookId = book.id
                val bookProgress = tracks.getTrackStartTime(track) + progress
                val bookDuration = tracks.getDuration()

                // [ChapterDebug] Log the difference between player position and DB saved progress
                Timber.d(
                    "[ChapterDebug] ProgressUpdater.updateProgress: " +
                        "playerPosition=$progress, " +
                        "dbProgress=${track.progress}, " +
                        "diff=${progress - track.progress}, " +
                        "trackId=$trackId, " +
                        "playbackState=$playbackState",
                )

                // Update PlaybackStateController with current position
                if (trackIndex >= 0) {
                    playbackStateController.updatePosition(trackIndex, progress)
                }

                // Use the current progress from the player, not the stale value from DB
                val trackWithCurrentProgress = track.copy(progress = progress)

                // [ChapterDebug] Calculate expected chapter based on player position vs DB position
                val chapters = if (book?.chapters?.isNotEmpty() == true) book.chapters else tracks.asChapterList(bookId)
                if (chapters.isNotEmpty()) {
                    val chapterFromPlayerPos = chapters.getChapterAt(trackId, progress)
                    val chapterFromDbPos = chapters.getChapterAt(trackId, track.progress)
                    Timber.d(
                        "[ChapterDebug] ProgressUpdater BEFORE update: " +
                            "chapterFromPlayerPos='${chapterFromPlayerPos.title}' (idx=${chapterFromPlayerPos.index}), " +
                            "chapterFromDbPos='${chapterFromDbPos.title}' (idx=${chapterFromDbPos.index}), " +
                            "willUsePlayerPos=$progress",
                    )
                }

                // Keep calling currentlyPlaying.update() for backward compatibility
                // This will be a no-op once CurrentlyPlayingSingleton fully observes the controller
                currentlyPlaying.update(
                    book = book ?: EMPTY_AUDIOBOOK,
                    track = trackWithCurrentProgress,
                    tracks = tracks,
                )

                // Update local DB
                if (!prefsRepo.debugOnlyDisableLocalProgressTracking) {
                    updateLocalProgress(
                        bookId = bookId,
                        currentTime = currentTime,
                        trackProgress = progress,
                        trackId = trackId,
                        bookProgress = bookProgress,
                        tracks = tracks,
                        bookDuration = bookDuration,
                    )
                } else {
                    Timber.w(
                        "[ProgressSaveRestoreDebug] SKIPPED WRITE: debugOnlyDisableLocalProgressTracking is TRUE - " +
                            "progress will NOT be saved to database (bookId=$bookId, trackId=$trackId, progress=$progress)",
                    )
                }

                // Update server once every [networkCallFrequency] calls, or when manual updates
                // have been specifically requested
                if (forceNetworkUpdate || tickCounter % NETWORK_CALL_FREQUENCY == 0L) {
                    // Pass libraryId from state to avoid DB lookup race condition
                    updateNetworkProgress(
                        trackId = trackId,
                        playbackState = playbackState,
                        trackProgress = progress,
                        bookProgress = bookProgress,
                        libraryId = book.libraryId,
                    )
                }
            }
        }

        private suspend fun updateNetworkProgress(
            trackId: String,
            playbackState: String,
            trackProgress: Long,
            bookProgress: Long,
            libraryId: String,
        ) {
            // libraryId is now passed from updateProgress() to avoid race condition
            if (libraryId.isEmpty()) {
                Timber.w("libraryId is empty, skipping network update")
                return
            }

            val syncWorkerConstraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val inputData =
                PlexSyncScrobbleWorker.makeWorkerData(
                    trackId = trackId,
                    playbackState = playbackState,
                    trackProgress = trackProgress,
                    bookProgress = bookProgress,
                    libraryId = libraryId,
                )
            val worker =
                OneTimeWorkRequestBuilder<PlexSyncScrobbleWorker>()
                    .setInputData(inputData)
                    .setConstraints(syncWorkerConstraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                    .build()

            workManager
                .beginUniqueWork(trackId.toString(), ExistingWorkPolicy.REPLACE, worker)
                .enqueue()
        }

        private suspend fun updateLocalProgress(
            bookId: String,
            currentTime: Long,
            trackProgress: Long,
            trackId: String,
            bookProgress: Long,
            tracks: List<MediaItemTrack>,
            bookDuration: Long,
        ) {
            tickCounter++
            bookRepository.updateProgress(bookId, currentTime, bookProgress)
            trackRepository.updateTrackProgress(trackProgress, trackId, currentTime)
            Timber.d(
                "[ProgressSaveRestoreDebug] WRITE: bookId=$bookId, trackId=$trackId, " +
                    "trackProgress=$trackProgress, bookProgress=$bookProgress, timestamp=$currentTime",
            )
            bookRepository.updateTrackData(
                bookId,
                bookProgress,
                tracks.getDuration(),
                tracks.size,
            )
            if (bookDuration - bookProgress <= BOOK_FINISHED_END_OFFSET_MILLIS) {
                Timber.i("Marking $bookId as finished")
                bookRepository.setWatched(bookId)
            }
        }

        override fun cancel() {
            handler.removeCallbacks(updateProgressAction)
        }
    }
