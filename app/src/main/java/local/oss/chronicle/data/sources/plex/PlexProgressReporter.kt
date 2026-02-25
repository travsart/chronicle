package local.oss.chronicle.data.sources.plex

import android.os.Build
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.data.sources.plex.model.getDuration
import local.oss.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_PAUSED
import local.oss.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_STOPPED
import local.oss.chronicle.features.player.ProgressUpdater.Companion.BOOK_FINISHED_END_OFFSET_MILLIS
import local.oss.chronicle.util.RetryConfig
import local.oss.chronicle.util.RetryResult
import local.oss.chronicle.util.withRetry
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports playback progress to Plex servers with library-aware routing.
 *
 * Creates request-scoped Retrofit instances to avoid global state mutation.
 * Each progress report uses the correct server URL and auth token for the library.
 *
 * This class eliminates the race condition in multi-library setups where concurrent
 * workers would mutate global PlexConfig.url and interfere with each other.
 *
 * @see ServerConnectionResolver
 * @see PlexSyncScrobbleWorker
 */
@Singleton
class PlexProgressReporter
    @Inject
    constructor(
        private val plexConfig: PlexConfig,
        private val serverConnectionResolver: ServerConnectionResolver,
        private val libraryRepository: LibraryRepository,
    ) {
        companion object {
            /**
             * Retry configuration for progress reporting.
             * Uses shorter delays than default since progress updates are frequent.
             */
            private val PROGRESS_RETRY_CONFIG =
                RetryConfig(
                    maxAttempts = 3,
                    initialDelayMs = 1000L, // 1 second
                    maxDelayMs = 5000L, // Max 5 seconds
                    multiplier = 2.0,
                )
        }

        /**
         * Reports progress to Plex using library-specific connection.
         *
         * @param connection Library-specific server URL and auth token
         * @param track The track being played
         * @param book The audiobook containing the track
         * @param tracks All tracks in the audiobook
         * @param trackProgress Current position in track (ms)
         * @param bookProgress Current position in book (ms)
         * @param playbackState Plex state: "playing", "paused", or "stopped"
         */
        suspend fun reportProgress(
            connection: ServerConnection,
            track: MediaItemTrack,
            book: Audiobook,
            tracks: List<MediaItemTrack>,
            trackProgress: Long,
            bookProgress: Long,
            playbackState: String,
        ) {
            // Create request-scoped PlexService with library-specific base URL
            val service = createScopedService(connection)

            // Extract numeric IDs for API calls (strip "plex:" prefix)
            val numericTrackId =
                track.id.removePrefix("plex:").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid track ID: ${track.id}")

            val numericBookId =
                book.id.removePrefix("plex:").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid book ID: ${book.id}")

            // Report timeline progress with retry
            when (
                val result =
                    withRetry(
                        config = PROGRESS_RETRY_CONFIG,
                        shouldRetry = { error -> isRetryableError(error) },
                        onRetry = { attempt, delay, error ->
                            Timber.w(
                                "Progress report attempt $attempt failed, retrying in ${delay}ms: ${error.message}",
                            )
                        },
                    ) { _ ->
                        service.progress(
                            ratingKey = numericTrackId.toString(),
                            offset = trackProgress.toString(),
                            playbackTime = trackProgress,
                            playQueueItemId = track.playQueueItemID,
                            key = "${MediaItemTrack.PARENT_KEY_PREFIX}$numericTrackId",
                            duration = track.duration,
                            playState = playbackState,
                            hasMde = 1,
                        )
                    }
            ) {
                is RetryResult.Success -> {
                    Timber.i("Synced progress for ${book.title}: $playbackState at ${trackProgress}ms")
                }
                is RetryResult.Failure -> {
                    Timber.e("Failed to sync progress after ${result.attemptsMade} attempts: ${result.error.message}")
                    throw result.error.cause ?: IOException("Failed to sync progress: ${result.error.message}")
                }
            }

            // Mark track as watched if finished (within 1 second of end)
            val isTrackFinished = trackProgress > track.duration - 1000
            if (isTrackFinished) {
                when (
                    val result =
                        withRetry(
                            config = PROGRESS_RETRY_CONFIG,
                            shouldRetry = { error -> isRetryableError(error) },
                        ) { _ ->
                            service.watched(numericTrackId.toString())
                        }
                ) {
                    is RetryResult.Success -> {
                        Timber.i("Marked track watched: ${track.title}")
                    }
                    is RetryResult.Failure -> {
                        Timber.e("Failed to mark track watched: ${result.error.message}")
                        // Don't throw - this is not critical
                    }
                }
            }

            // Mark book as watched if finished
            val isBookFinished = isBookCompleted(bookProgress, tracks.getDuration(), playbackState)
            if (isBookFinished) {
                when (
                    val result =
                        withRetry(
                            config = PROGRESS_RETRY_CONFIG,
                            shouldRetry = { error -> isRetryableError(error) },
                        ) { _ ->
                            service.watched(numericBookId.toString())
                        }
                ) {
                    is RetryResult.Success -> {
                        Timber.i("Marked book watched: ${book.title}")
                    }
                    is RetryResult.Failure -> {
                        Timber.e("Failed to mark book watched: ${result.error.message}")
                        // Don't throw - this is not critical
                    }
                }
            }
        }

        /**
         * Starts a media session on the Plex server for the given audiobook.
         * Uses library-specific server connection to ensure the session is created
         * on the correct server in multi-library setups.
         *
         * This call is non-critical - failure will be logged but will NOT prevent playback.
         *
         * @param bookId The audiobook's rating key (with "plex:" prefix)
         * @param libraryId The library ID this audiobook belongs to
         */
        suspend fun startMediaSession(
            bookId: String,
            libraryId: String,
        ) {
            try {
                // Extract numeric book ID
                val numericBookId =
                    bookId.removePrefix("plex:").toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid book ID: $bookId")

                // Resolve library-specific server connection
                val connection =
                    try {
                        serverConnectionResolver.resolve(libraryId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to resolve server for library: $libraryId")
                        return // Non-critical, don't prevent playback
                    }

                // Get library to retrieve serverId (machine identifier)
                val library =
                    try {
                        libraryRepository.getLibraryById(libraryId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to get library for id: $libraryId")
                        return // Non-critical, don't prevent playback
                    }

                if (library == null) {
                    Timber.w("Library not found for id: $libraryId. Cannot start media session.")
                    return // Non-critical, don't prevent playback
                }

                val serverId = library.serverId
                if (serverId.isEmpty()) {
                    Timber.w("Server ID not set for library: $libraryId. Cannot start media session.")
                    return // Non-critical, don't prevent playback
                }

                // Create library-scoped service
                val service = createScopedService(connection)

                // Build the media item URI
                val uri = getMediaItemUri(serverId, numericBookId.toString())

                // Start media session
                service.startMediaSession(uri)
                Timber.i("Started media session for book $bookId on server $serverId (library: $libraryId)")
            } catch (e: Exception) {
                // This is non-critical - log but never throw
                Timber.e(e, "Failed to start media session for book $bookId: ${e.message}")
            }
        }

        /**
         * Creates a library-scoped PlexService with specific base URL and auth token.
         * This instance is used only for this request and discarded after.
         *
         * @param connection Server connection with URL and auth token
         * @return PlexMediaService instance configured for this library
         */
        private fun createScopedService(connection: ServerConnection): PlexMediaService {
            val baseUrl = connection.serverUrl ?: throw IllegalStateException("No server URL in connection")
            val authToken = connection.authToken ?: throw IllegalStateException("No auth token in connection")

            // Create OkHttp client with request-scoped interceptor
            val client =
                OkHttpClient.Builder()
                    .addInterceptor(createScopedInterceptor(authToken))
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()

            // Create Retrofit instance with library-specific base URL
            val retrofit =
                Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create())
                    .build()

            return retrofit.create(PlexMediaService::class.java)
        }

        /**
         * Creates an OkHttp interceptor with request-scoped auth token.
         * Similar to PlexInterceptor but uses provided token instead of global state.
         *
         * @param authToken The auth token for this specific request
         * @return Interceptor that adds Plex headers to requests
         */
        private fun createScopedInterceptor(authToken: String): Interceptor {
            return Interceptor { chain ->
                val request =
                    chain.request().newBuilder()
                        .header("Accept", "application/json")
                        .header("X-Plex-Platform", "Android")
                        .header("X-Plex-Provides", "player")
                        .header("X-Plex-Client-Identifier", plexConfig.sessionIdentifier)
                        .header("X-Plex-Version", BuildConfig.VERSION_NAME)
                        .header("X-Plex-Product", APP_NAME)
                        .header("X-Plex-Platform-Version", Build.VERSION.RELEASE)
                        .header("X-Plex-Device", Build.MODEL)
                        .header("X-Plex-Device-Name", Build.MODEL)
                        .header("X-Plex-Token", authToken) // Request-scoped token
                        .build()

                chain.proceed(request)
            }
        }

        /**
         * Determines if book is completed based on progress.
         * Called when playback pauses/stops to avoid auto-marking during playback.
         *
         * @param bookProgress Current position in book (ms)
         * @param bookDuration Total duration of book (ms)
         * @param playbackState Current playback state
         * @return true if book should be marked as watched
         */
        private fun isBookCompleted(
            bookProgress: Long,
            bookDuration: Long,
            playbackState: String,
        ): Boolean {
            // Only consider book finished when user explicitly pauses/stops near the end
            val isNearEnd = bookDuration - bookProgress < BOOK_FINISHED_END_OFFSET_MILLIS
            val hasUserStopped = playbackState == PLEX_STATE_PAUSED || playbackState == PLEX_STATE_STOPPED
            return isNearEnd && hasUserStopped
        }

        /**
         * Determines if an error is transient and should be retried.
         *
         * Retryable errors:
         * - Network timeouts and connection failures
         * - HTTP 5xx server errors
         *
         * Non-retryable errors:
         * - HTTP 401/403 authentication/authorization failures
         * - HTTP 404 not found
         * - Other client errors (4xx)
         *
         * @param error The error to check
         * @return true if the error is retryable
         */
        private fun isRetryableError(error: Throwable): Boolean {
            return when (error) {
                is SocketTimeoutException,
                is UnknownHostException,
                is ConnectException,
                is IOException,
                -> true
                is HttpException -> {
                    // Only retry server errors (5xx), not client errors (4xx)
                    // Specifically do NOT retry 401/403 auth errors
                    error.code() in 500..599
                }
                else -> false
            }
        }
    }
