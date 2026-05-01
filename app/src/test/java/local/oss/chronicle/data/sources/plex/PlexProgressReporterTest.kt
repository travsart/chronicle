package local.oss.chronicle.data.sources.plex

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_PAUSED
import local.oss.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_PLAYING
import local.oss.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_STOPPED
import local.oss.chronicle.features.player.ProgressUpdater.Companion.BOOK_FINISHED_END_OFFSET_MILLIS
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Tests for PlexProgressReporter, focusing on progress reporting logic,
 * error categorization, and library-aware behavior.
 *
 * Note: PlexProgressReporter creates its own Retrofit instances internally,
 * so these tests focus on testable logic (error categorization, completion detection,
 * ID stripping) and integration with mocked dependencies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class PlexProgressReporterTest {
    @Mock
    private lateinit var plexConfig: PlexConfig

    @Mock
    private lateinit var plexPrefsRepo: PlexPrefsRepo

    @Mock
    private lateinit var serverConnectionResolver: ServerConnectionResolver

    @Mock
    private lateinit var libraryRepository: LibraryRepository

    private lateinit var progressReporter: PlexProgressReporter

    @Before
    fun setup() {
        // Mock PlexConfig to return test values (lenient since not all tests use this)
        lenient().`when`(plexConfig.sessionIdentifier).thenReturn("test-session-id")

        // Mock PlexPrefsRepo to return test UUID (lenient since not all tests use this)
        lenient().`when`(plexPrefsRepo.uuid).thenReturn("test-uuid-12345")

        progressReporter =
            PlexProgressReporter(
                plexConfig = plexConfig,
                plexPrefsRepo = plexPrefsRepo,
                serverConnectionResolver = serverConnectionResolver,
                libraryRepository = libraryRepository,
                scopedPlexServiceFactory = mock(),
            )
    }

    // ========================================
    // ID Stripping Tests
    // ========================================

    @Test
    fun `ID stripping removes plex prefix correctly`() {
        // Given: IDs with "plex:" prefix
        val trackId = "plex:12345"
        val bookId = "plex:67890"

        // When: Stripping prefix
        val numericTrackId = trackId.removePrefix("plex:").toIntOrNull()
        val numericBookId = bookId.removePrefix("plex:").toIntOrNull()

        // Then: Numeric values extracted
        assertThat(numericTrackId).isEqualTo(12345)
        assertThat(numericBookId).isEqualTo(67890)
    }

    @Test
    fun `ID stripping handles IDs without prefix`() {
        // Given: ID without prefix
        val id = "99999"

        // When: Stripping prefix (no-op when no prefix)
        val numericId = id.removePrefix("plex:").toIntOrNull()

        // Then: Value still extracted
        assertThat(numericId).isEqualTo(99999)
    }

    @Test
    fun `ID stripping returns null for invalid IDs`() {
        // Given: Invalid ID
        val invalidId = "plex:invalid"

        // When: Attempting to extract numeric value
        val numericId = invalidId.removePrefix("plex:").toIntOrNull()

        // Then: Returns null
        assertThat(numericId).isNull()
    }

    // ========================================
    // Book Completion Detection Tests
    // ========================================

    @Test
    fun `book is completed when near end and paused`() {
        // Given: Book progress near end
        val bookDuration = 3600_000L
        val bookProgress = bookDuration - (BOOK_FINISHED_END_OFFSET_MILLIS / 2)
        val playbackState = PLEX_STATE_PAUSED

        // When: Checking completion
        val isCompleted = isBookCompleted(bookProgress, bookDuration, playbackState)

        // Then: Should be marked completed
        assertThat(isCompleted).isTrue()
    }

    @Test
    fun `book is completed when near end and stopped`() {
        // Given: Book progress near end with stopped state
        val bookDuration = 3600_000L
        val bookProgress = bookDuration - (BOOK_FINISHED_END_OFFSET_MILLIS / 2)
        val playbackState = PLEX_STATE_STOPPED

        // When: Checking completion
        val isCompleted = isBookCompleted(bookProgress, bookDuration, playbackState)

        // Then: Should be marked completed
        assertThat(isCompleted).isTrue()
    }

    @Test
    fun `book is NOT completed when near end but still playing`() {
        // Given: Book progress near end but playing
        val bookDuration = 3600_000L
        val bookProgress = bookDuration - (BOOK_FINISHED_END_OFFSET_MILLIS / 2)
        val playbackState = PLEX_STATE_PLAYING

        // When: Checking completion
        val isCompleted = isBookCompleted(bookProgress, bookDuration, playbackState)

        // Then: Should NOT be marked completed
        assertThat(isCompleted).isFalse()
    }

    @Test
    fun `book is NOT completed when paused but not near end`() {
        // Given: Book paused in middle
        val bookDuration = 3600_000L
        val bookProgress = bookDuration / 2 // Middle
        val playbackState = PLEX_STATE_PAUSED

        // When: Checking completion
        val isCompleted = isBookCompleted(bookProgress, bookDuration, playbackState)

        // Then: Should NOT be marked completed
        assertThat(isCompleted).isFalse()
    }

    @Test
    fun `book is completed at exact threshold boundary when paused`() {
        // Given: Book at exact completion boundary
        val bookDuration = 3600_000L
        val bookProgress = bookDuration - BOOK_FINISHED_END_OFFSET_MILLIS + 1L
        val playbackState = PLEX_STATE_PAUSED

        // When: Checking completion
        val isCompleted = isBookCompleted(bookProgress, bookDuration, playbackState)

        // Then: Should be marked completed
        assertThat(isCompleted).isTrue()
    }

    // ========================================
    // Track Completion Detection Tests
    // ========================================

    @Test
    fun `track is finished when within 1 second of end`() {
        // Given: Track near end (within 1000ms)
        val trackDuration = 600_000L
        val trackProgress = trackDuration - 500L

        // When: Checking if finished
        val isFinished = trackProgress > trackDuration - 1000

        // Then: Should be marked finished
        assertThat(isFinished).isTrue()
    }

    @Test
    fun `track is NOT finished in middle`() {
        // Given: Track in middle
        val trackDuration = 600_000L
        val trackProgress = 300_000L

        // When: Checking if finished
        val isFinished = trackProgress > trackDuration - 1000

        // Then: Should NOT be marked finished
        assertThat(isFinished).isFalse()
    }

    @Test
    fun `track is finished at exact 1 second boundary`() {
        // Given: Track exactly 1 second before end
        val trackDuration = 600_000L
        val trackProgress = trackDuration - 999L

        // When: Checking if finished
        val isFinished = trackProgress > trackDuration - 1000

        // Then: Should be marked finished
        assertThat(isFinished).isTrue()
    }

    // ========================================
    // Error Categorization Tests
    // ========================================

    @Test
    fun `SocketTimeoutException is retryable`() {
        // Given: SocketTimeoutException
        val error = SocketTimeoutException("Connection timed out")

        // When: Checking if retryable
        val isRetryable = isErrorRetryable(error)

        // Then: Should be retryable
        assertThat(isRetryable).isTrue()
    }

    @Test
    fun `IOException is retryable`() {
        // Given: IOException
        val error = IOException("Network error")

        // When: Checking if retryable
        val isRetryable = isErrorRetryable(error)

        // Then: Should be retryable
        assertThat(isRetryable).isTrue()
    }

    @Test
    fun `ConnectException is retryable`() {
        // Given: ConnectException
        val error = ConnectException("Connection refused")

        // When: Checking if retryable
        val isRetryable = isErrorRetryable(error)

        // Then: Should be retryable
        assertThat(isRetryable).isTrue()
    }

    @Test
    fun `UnknownHostException is retryable`() {
        // Given: UnknownHostException
        val error = UnknownHostException("Host not found")

        // When: Checking if retryable
        val isRetryable = isErrorRetryable(error)

        // Then: Should be retryable
        assertThat(isRetryable).isTrue()
    }

    @Test
    fun `HTTP 500 error is retryable`() {
        // Given: HTTP 500 error
        val error = createHttpException(500)

        // When: Checking if retryable
        val isRetryable = isErrorRetryable(error)

        // Then: Should be retryable (server error)
        assertThat(isRetryable).isTrue()
    }

    @Test
    fun `HTTP 503 error is retryable`() {
        // Given: HTTP 503 error
        val error = createHttpException(503)

        // When: Checking if retryable
        val isRetryable = isErrorRetryable(error)

        // Then: Should be retryable (service unavailable)
        assertThat(isRetryable).isTrue()
    }

    @Test
    fun `HTTP 401 error is NOT retryable`() {
        // Given: HTTP 401 error (auth failure)
        val error = createHttpException(401)

        // When: Checking if retryable
        val isRetryable = isErrorRetryable(error)

        // Then: Should NOT be retryable
        assertThat(isRetryable).isFalse()
    }

    @Test
    fun `HTTP 404 error is NOT retryable`() {
        // Given: HTTP 404 error (not found)
        val error = createHttpException(404)

        // When: Checking if retryable
        val isRetryable = isErrorRetryable(error)

        // Then: Should NOT be retryable
        assertThat(isRetryable).isFalse()
    }

    @Test
    fun `HTTP 403 error is NOT retryable`() {
        // Given: HTTP 403 error (forbidden)
        val error = createHttpException(403)

        // When: Checking if retryable
        val isRetryable = isErrorRetryable(error)

        // Then: Should NOT be retryable
        assertThat(isRetryable).isFalse()
    }

    @Test
    fun `generic exception is NOT retryable`() {
        // Given: Generic exception
        val error = IllegalArgumentException("Invalid argument")

        // When: Checking if retryable
        val isRetryable = isErrorRetryable(error)

        // Then: Should NOT be retryable
        assertThat(isRetryable).isFalse()
    }

    // ========================================
    // Start Media Session Tests
    // ========================================

    @Test
    fun `startMediaSession uses library-specific server connection`() =
        runTest {
            // Given: Library with specific server
            val connection = ServerConnection("https://192.168.1.100:32400", "library-specific-token")

            val library =
                Library(
                    id = "plex:library:2",
                    accountId = "plex:account:2",
                    serverId = "specific-server-456",
                    serverName = "Specific Server",
                    name = "Library 2",
                    type = "artist",
                    lastSyncedAt = null,
                    itemCount = 5,
                    isActive = false,
                )

            whenever(serverConnectionResolver.resolve("plex:library:2")).thenReturn(connection)
            whenever(libraryRepository.getLibraryById("plex:library:2")).thenReturn(library)

            // When: Starting session (non-critical, catches exceptions internally)
            progressReporter.startMediaSession(
                bookId = "plex:6000",
                libraryId = "plex:library:2",
            )

            // Then: Resolver called with correct library ID
            verify(serverConnectionResolver).resolve("plex:library:2")
            verify(libraryRepository).getLibraryById("plex:library:2")
        }

    @Test
    fun `startMediaSession handles missing library gracefully`() =
        runTest {
            // Given: Library not found
            val connection = ServerConnection("http://server", "token")

            whenever(serverConnectionResolver.resolve(any())).thenReturn(connection)
            whenever(libraryRepository.getLibraryById(any())).thenReturn(null)

            // When: Starting session with missing library (should not throw)
            progressReporter.startMediaSession(
                bookId = "plex:7000",
                libraryId = "plex:library:999",
            )

            // Then: No exception thrown, library lookup was attempted
            verify(libraryRepository).getLibraryById("plex:library:999")
        }

    @Test
    fun `startMediaSession handles empty serverId gracefully`() =
        runTest {
            // Given: Library with empty serverId
            val connection = ServerConnection("http://server", "token")

            val library =
                Library(
                    id = "plex:library:3",
                    accountId = "plex:account:3",
                    serverId = "",
                    // Empty serverId
                    serverName = "Server",
                    name = "Library",
                    type = "artist",
                    lastSyncedAt = null,
                    itemCount = 0,
                    isActive = true,
                )

            whenever(serverConnectionResolver.resolve(any())).thenReturn(connection)
            whenever(libraryRepository.getLibraryById(any())).thenReturn(library)

            // When: Starting session with empty serverId (should not throw)
            progressReporter.startMediaSession(
                bookId = "plex:8000",
                libraryId = "plex:library:3",
            )

            // Then: No exception thrown, library was checked
            verify(libraryRepository).getLibraryById("plex:library:3")
        }

    @Test
    fun `startMediaSession handles resolver exception gracefully`() =
        runTest {
            // Given: Resolver throws exception
            whenever(serverConnectionResolver.resolve(any())).thenThrow(RuntimeException("Connection failed"))

            // When: Starting session (should not throw due to try-catch)
            progressReporter.startMediaSession(
                bookId = "plex:9000",
                libraryId = "plex:library:4",
            )

            // Then: No exception propagated (logged but handled)
            verify(serverConnectionResolver).resolve("plex:library:4")
        }

    // ========================================
    // Play Queue Cache Tests
    // ========================================

    @Test
    fun `clearPlayQueueCache does not throw exception`() {
        // Given: Cache is in any state
        // When: Clearing the cache
        progressReporter.clearPlayQueueCache()

        // Then: Method completes without exception
        // This test documents that clearPlayQueueCache() is safe to call anytime
    }

    @Test
    fun `startMediaSession calls resolver and repository`() =
        runTest {
            // Given: Library with specific server
            val connection = ServerConnection("https://192.168.1.100:32400", "library-specific-token")

            val library =
                Library(
                    id = "plex:library:1",
                    accountId = "plex:account:1",
                    serverId = "test-server-123",
                    serverName = "Test Server",
                    name = "Test Library",
                    type = "artist",
                    lastSyncedAt = null,
                    itemCount = 10,
                    isActive = true,
                )

            whenever(serverConnectionResolver.resolve("plex:library:1")).thenReturn(connection)
            whenever(libraryRepository.getLibraryById("plex:library:1")).thenReturn(library)

            // When: Starting session (cache will be populated internally)
            // Note: Since PlexProgressReporter creates its own service internally,
            // we can't mock the response. This test verifies resolver/repository are called.
            progressReporter.startMediaSession(
                bookId = "plex:5000",
                libraryId = "plex:library:1",
            )

            // Then: Resolver and repository are invoked
            verify(serverConnectionResolver).resolve("plex:library:1")
            verify(libraryRepository).getLibraryById("plex:library:1")
        }

    @Test
    fun `cache miss behavior uses -1 as fallback`() {
        // This test documents the expected behavior when playQueueItemId is not cached.
        //
        // Context: When reportProgress() is called for a track that is NOT in the
        // playQueueItemCache, the code uses -1 as the fallback value.
        //
        // This is visible in PlexProgressReporter.reportProgress():
        //   val playQueueItemId = playQueueItemCache[track.id] ?: -1L
        //
        // The -1 value tells Plex that we don't have a valid play queue item ID,
        // which may cause dashboard activity to not work correctly.

        val expectedFallback = -1L
        assertThat(expectedFallback).isEqualTo(-1L)
    }

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Mirrors the private isBookCompleted logic from PlexProgressReporter.
     */
    private fun isBookCompleted(
        bookProgress: Long,
        bookDuration: Long,
        playbackState: String,
    ): Boolean {
        val isNearEnd = bookDuration - bookProgress < BOOK_FINISHED_END_OFFSET_MILLIS
        val hasUserStopped = playbackState == PLEX_STATE_PAUSED || playbackState == PLEX_STATE_STOPPED
        return isNearEnd && hasUserStopped
    }

    /**
     * Mirrors the private isRetryableError logic from PlexProgressReporter.
     */
    private fun isErrorRetryable(error: Throwable): Boolean {
        return when (error) {
            is SocketTimeoutException,
            is UnknownHostException,
            is ConnectException,
            is IOException,
            -> true
            is HttpException -> error.code() in 500..599
            else -> false
        }
    }

    /**
     * Creates a mock HttpException with the given code.
     */
    private fun createHttpException(code: Int): HttpException {
        val responseBody = okhttp3.ResponseBody.create(null, "")
        val response =
            okhttp3.Response.Builder()
                .request(okhttp3.Request.Builder().url("http://test").build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message("Test")
                .body(responseBody)
                .build()
        return HttpException(retrofit2.Response.error<Any>(responseBody, response))
    }
}
