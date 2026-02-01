package local.oss.chronicle.features.player

import android.content.Context
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.local.IBookRepository
import local.oss.chronicle.data.local.ITrackRepository
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.EMPTY_AUDIOBOOK
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo.LoginState
import local.oss.chronicle.data.sources.plex.PlaybackUrlResolver
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.features.currentlyplaying.CurrentlyPlaying
import local.oss.chronicle.util.Event
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import timber.log.Timber

/**
 * Unit tests for the voice search fallback feature in AudiobookMediaSessionCallback.
 *
 * Tests verify that when Android Auto voice search returns no results:
 * - If fallback is enabled: plays the most recently played book (or random book if no recent)
 * - If fallback is disabled: shows "no results" error (original behavior)
 */
@ExperimentalCoroutinesApi
class VoiceSearchFallbackTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @RelaxedMockK
    private lateinit var plexPrefsRepo: PlexPrefsRepo

    @RelaxedMockK
    private lateinit var prefsRepo: PrefsRepo

    @RelaxedMockK
    private lateinit var plexConfig: PlexConfig

    @RelaxedMockK
    private lateinit var plexLoginRepo: IPlexLoginRepo

    @RelaxedMockK
    private lateinit var mediaController: MediaControllerCompat

    @RelaxedMockK
    private lateinit var dataSourceFactory: DefaultHttpDataSource.Factory

    @RelaxedMockK
    private lateinit var trackRepository: ITrackRepository

    @RelaxedMockK
    private lateinit var bookRepository: IBookRepository

    @RelaxedMockK
    private lateinit var trackListStateManager: TrackListStateManager

    @RelaxedMockK
    private lateinit var foregroundServiceController: ForegroundServiceController

    @RelaxedMockK
    private lateinit var serviceController: ServiceController

    @RelaxedMockK
    private lateinit var errorReporter: IPlaybackErrorReporter

    @RelaxedMockK
    private lateinit var mediaSession: MediaSessionCompat

    @RelaxedMockK
    private lateinit var appContext: Context

    @MockK
    private lateinit var currentlyPlaying: CurrentlyPlaying

    @RelaxedMockK
    private lateinit var progressUpdater: ProgressUpdater

    @RelaxedMockK
    private lateinit var playbackUrlResolver: PlaybackUrlResolver

    @RelaxedMockK
    private lateinit var playbackStateController: PlaybackStateController

    @RelaxedMockK
    private lateinit var defaultPlayer: ExoPlayer

    private lateinit var testScope: TestScope
    private lateinit var callback: AudiobookMediaSessionCallback
    private lateinit var testExceptionHandler: CoroutineExceptionHandler

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        testScope = TestScope()

        // Setup appContext string resource mocks
        every { appContext.getString(any()) } returns "Mock error message"
        every { appContext.getString(any(), any()) } returns "Mock error message with query"

        // Setup default authentication state (logged in fully)
        val loginEvent = MutableLiveData<Event<LoginState>>()
        loginEvent.value = Event(LoginState.LOGGED_IN_FULLY)
        every { plexLoginRepo.loginEvent } returns loginEvent

        // Create test exception handler that logs but doesn't crash
        testExceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                Timber.e(throwable, "Test coroutine exception")
            }

        callback =
            AudiobookMediaSessionCallback(
                plexPrefsRepo = plexPrefsRepo,
                prefsRepo = prefsRepo,
                plexConfig = plexConfig,
                plexLoginRepo = plexLoginRepo,
                mediaController = mediaController,
                dataSourceFactory = dataSourceFactory,
                trackRepository = trackRepository,
                bookRepository = bookRepository,
                serviceScope = testScope as CoroutineScope,
                trackListStateManager = trackListStateManager,
                foregroundServiceController = foregroundServiceController,
                serviceController = serviceController,
                errorReporter = errorReporter,
                mediaSession = mediaSession,
                appContext = appContext,
                currentlyPlaying = currentlyPlaying,
                progressUpdater = progressUpdater,
                playbackUrlResolver = playbackUrlResolver,
                playbackStateController = playbackStateController,
                coroutineExceptionHandler = testExceptionHandler,
                defaultPlayer = defaultPlayer,
            )
    }

    /**
     * Test Case 1: Fallback enabled, search returns empty, recent book exists
     *
     * When: Voice search returns no results and fallback is enabled
     * And: A recently played book exists
     * Then: The recently played book should be played
     */
    @Test
    fun `handleSearchSuspend_searchReturnsEmpty_fallbackEnabled_playsRecentBook`() =
        runTest {
            // Given: Fallback is enabled
            every { prefsRepo.voiceSearchFallbackEnabled } returns true

            // Given: Search returns empty results
            coEvery { bookRepository.searchAsync("nonexistent query") } returns emptyList()

            // Given: A recently played book exists
            val recentBook =
                Audiobook(
                    id = 42,
                    source = 1L,
                    title = "Recently Played Book",
                    author = "Test Author",
                    duration = 3600000,
                    lastViewedAt = System.currentTimeMillis(),
                    viewCount = 5,
                )
            coEvery { bookRepository.getMostRecentlyPlayed() } returns recentBook
            coEvery { trackRepository.getTracksForAudiobookAsync(42) } returns emptyList()
            coEvery { bookRepository.getAudiobookAsync(42) } returns recentBook

            // When: Voice search is performed with no results
            callback.onPlayFromSearch("nonexistent query", null)

            // Advance time to allow coroutine to complete
            testScope.testScheduler.advanceUntilIdle()
            testScope.testScheduler.runCurrent()

            // Then: No error is set (playback proceeds with fallback book)
            verify(exactly = 0) {
                errorReporter.setPlaybackStateError(
                    PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                    any(),
                )
            }
        }

    /**
     * Test Case 2: Fallback enabled, no recent book, random book exists
     *
     * When: Voice search returns no results and fallback is enabled
     * And: No recently played book exists
     * And: A random book can be selected
     * Then: The random book should be played
     */
    @Test
    fun `handleSearchSuspend_searchReturnsEmpty_fallbackEnabled_noRecentBook_playsRandomBook`() =
        runTest {
            // Given: Fallback is enabled
            every { prefsRepo.voiceSearchFallbackEnabled } returns true

            // Given: Search returns empty results
            coEvery { bookRepository.searchAsync("nonexistent query") } returns emptyList()

            // Given: No recently played book exists
            coEvery { bookRepository.getMostRecentlyPlayed() } returns EMPTY_AUDIOBOOK

            // Given: A random book is available
            val randomBook =
                Audiobook(
                    id = 100,
                    source = 1L,
                    title = "Random Book",
                    author = "Random Author",
                    duration = 5400000,
                )
            coEvery { bookRepository.getRandomBookAsync() } returns randomBook
            coEvery { trackRepository.getTracksForAudiobookAsync(100) } returns emptyList()
            coEvery { bookRepository.getAudiobookAsync(100) } returns randomBook

            // When: Voice search is performed with no results
            callback.onPlayFromSearch("nonexistent query", null)

            // Advance time to allow coroutine to complete
            testScope.testScheduler.advanceUntilIdle()
            testScope.testScheduler.runCurrent()

            // Then: No error is set (playback proceeds with random book)
            verify(exactly = 0) {
                errorReporter.setPlaybackStateError(
                    PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                    any(),
                )
            }
        }

    /**
     * Test Case 3: Fallback enabled, but library is empty
     *
     * When: Voice search returns no results and fallback is enabled
     * And: No recently played book exists
     * And: No books exist in the library
     * Then: An error should be shown indicating the library is empty
     */
    @Test
    fun `handleSearchSuspend_searchReturnsEmpty_fallbackEnabled_noBooks_showsError`() =
        runTest {
            // Given: Fallback is enabled
            every { prefsRepo.voiceSearchFallbackEnabled } returns true

            // Given: Search returns empty results
            coEvery { bookRepository.searchAsync("nonexistent query") } returns emptyList()

            // Given: No recently played book exists
            coEvery { bookRepository.getMostRecentlyPlayed() } returns EMPTY_AUDIOBOOK

            // Given: No books in library (random book also returns empty)
            coEvery { bookRepository.getRandomBookAsync() } returns EMPTY_AUDIOBOOK

            // When: Voice search is performed with no results
            callback.onPlayFromSearch("nonexistent query", null)

            // Advance time to allow coroutine to complete
            testScope.testScheduler.advanceUntilIdle()
            testScope.testScheduler.runCurrent()

            // Then: Error is set indicating library is empty
            verify(timeout = 1000) {
                errorReporter.setPlaybackStateError(
                    PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                    any(),
                )
            }
        }

    /**
     * Test Case 4: Fallback disabled, search returns no results
     *
     * When: Voice search returns no results and fallback is disabled
     * Then: An error should be shown with "no results for query" message (original behavior)
     */
    @Test
    fun `handleSearchSuspend_searchReturnsEmpty_fallbackDisabled_showsError`() =
        runTest {
            // Given: Fallback is disabled
            every { prefsRepo.voiceSearchFallbackEnabled } returns false

            // Given: Search returns empty results
            coEvery { bookRepository.searchAsync("specific query") } returns emptyList()

            // When: Voice search is performed with no results
            callback.onPlayFromSearch("specific query", null)

            // Advance time to allow coroutine to complete
            testScope.testScheduler.advanceUntilIdle()
            testScope.testScheduler.runCurrent()

            // Then: Error is set with "no results" message
            verify(timeout = 1000) {
                errorReporter.setPlaybackStateError(
                    PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                    any(),
                )
            }
        }

    /**
     * Test Case 5: Search returns results (fallback should not be used)
     *
     * When: Voice search returns matching results
     * Then: The first result should be played (fallback should not be triggered)
     */
    @Test
    fun `handleSearchSuspend_searchReturnsResults_playsFirstResult`() =
        runTest {
            // Given: Fallback is enabled (but shouldn't matter since search succeeds)
            every { prefsRepo.voiceSearchFallbackEnabled } returns true

            // Given: Search returns matching results
            val matchingBook =
                Audiobook(
                    id = 200,
                    source = 1L,
                    title = "Matching Book Title",
                    author = "Matching Author",
                    duration = 7200000,
                )
            coEvery { bookRepository.searchAsync("matching query") } returns listOf(matchingBook)
            coEvery { trackRepository.getTracksForAudiobookAsync(200) } returns emptyList()
            coEvery { bookRepository.getAudiobookAsync(200) } returns matchingBook

            // When: Voice search is performed with a successful query
            callback.onPlayFromSearch("matching query", null)

            // Advance time to allow coroutine to complete
            testScope.testScheduler.advanceUntilIdle()
            testScope.testScheduler.runCurrent()

            // Then: No error is set (playback proceeds with search result)
            verify(exactly = 0) {
                errorReporter.setPlaybackStateError(
                    PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                    any(),
                )
            }

            // Verify that getMostRecentlyPlayed was NOT called (fallback not used)
            coVerify(exactly = 0) {
                bookRepository.getMostRecentlyPlayed()
            }
        }
}
