package local.oss.chronicle.data.local

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.data.sources.plex.PlexMediaService
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.ScopedPlexServiceFactory
import local.oss.chronicle.data.sources.plex.ServerConnectionResolver
import local.oss.chronicle.data.sources.plex.model.PlexChapter
import local.oss.chronicle.data.sources.plex.model.PlexDirectory
import local.oss.chronicle.data.sources.plex.model.PlexMediaContainer
import local.oss.chronicle.data.sources.plex.model.PlexMediaContainerWrapper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for ChapterRepository focusing on duplicate chapter prevention.
 *
 * Verifies the fix for the bug where chapters would duplicate on re-sync due to
 * the ChapterDatabase v1→v2 migration changing the primary key from `id` to auto-generated `uid`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class ChapterRepositoryTest {
    @Mock
    private lateinit var mockChapterDao: ChapterDao

    @Mock
    private lateinit var mockPrefsRepo: PrefsRepo

    @Mock
    private lateinit var mockPlexPrefs: PlexPrefsRepo

    @Mock
    private lateinit var mockScopedPlexServiceFactory: ScopedPlexServiceFactory

    @Mock
    private lateinit var mockServerConnectionResolver: ServerConnectionResolver

    @Mock
    private lateinit var mockScopedService: PlexMediaService

    private lateinit var chapterRepository: ChapterRepository

    private val testBookId = "plex:12345"
    private val testTrackId = "plex:471"
    private val testLibraryId = "plex:library:1"
    private val testServerUrl = "https://server.plex.direct:32400"
    private val testToken = "test-token"

    @Before
    fun setup() {
        chapterRepository =
            ChapterRepository(
                chapterDao = mockChapterDao,
                prefsRepo = mockPrefsRepo,
                plexPrefsRepo = mockPlexPrefs,
                scopedPlexServiceFactory = mockScopedPlexServiceFactory,
                serverConnectionResolver = mockServerConnectionResolver,
            )
    }

    /**
     * TEST: loadChapterData called multiple times does not create duplicates
     *
     * This is the core regression test for the duplicate chapters bug.
     * Before the fix, chapters would accumulate on each re-sync because
     * OnConflictStrategy.REPLACE didn't work with auto-generated uid primary key.
     *
     * After the fix, deleteByBookId() is called before insertAll() to prevent duplicates.
     */
    @Test
    fun `loadChapterData called multiple times does not create duplicates`() =
        runTest {
            // Given: A track with chapters and library connection
            val track =
                createMockTrack(
                    trackId = testTrackId,
                    libraryId = testLibraryId,
                )
            val connection = ServerConnection(testServerUrl, testToken)
            whenever(mockServerConnectionResolver.resolve(testLibraryId)).thenReturn(connection)
            whenever(mockScopedPlexServiceFactory.getOrCreateService(connection)).thenReturn(mockScopedService)

            // Mock API response with 3 chapters
            val chapterResponse = createMockChapterResponse(3)
            whenever(mockScopedService.retrieveChapterInfo(471)).thenReturn(chapterResponse)

            // When: loadChapterData is called twice for the same book
            chapterRepository.loadChapterData(testBookId, isAudiobookCached = false, tracks = listOf(track))
            chapterRepository.loadChapterData(testBookId, isAudiobookCached = false, tracks = listOf(track))

            // Then: deleteByBookId should be called twice (once per load)
            verify(mockChapterDao, times(2)).deleteByBookId(testBookId)

            // And: insertAll should be called twice (once per load)
            verify(mockChapterDao, times(2)).insertAll(any())

            // And: deleteByBookId must be called BEFORE insertAll each time
            val inOrder: InOrder = inOrder(mockChapterDao)
            // First call
            inOrder.verify(mockChapterDao).deleteByBookId(testBookId)
            inOrder.verify(mockChapterDao).insertAll(any())
            // Second call
            inOrder.verify(mockChapterDao).deleteByBookId(testBookId)
            inOrder.verify(mockChapterDao).insertAll(any())
        }

    /**
     * TEST: deleteByBookId is called before insertAll
     *
     * Verifies the order of operations to ensure atomic delete-then-insert pattern.
     */
    @Test
    fun `loadChapterData calls deleteByBookId before insertAll`() =
        runTest {
            // Given: Valid track and library connection
            val track =
                createMockTrack(
                    trackId = testTrackId,
                    libraryId = testLibraryId,
                )
            val connection = ServerConnection(testServerUrl, testToken)
            whenever(mockServerConnectionResolver.resolve(testLibraryId)).thenReturn(connection)
            whenever(mockScopedPlexServiceFactory.getOrCreateService(connection)).thenReturn(mockScopedService)

            val chapterResponse = createMockChapterResponse(5)
            whenever(mockScopedService.retrieveChapterInfo(471)).thenReturn(chapterResponse)

            // When: loadChapterData is called
            chapterRepository.loadChapterData(testBookId, isAudiobookCached = false, tracks = listOf(track))

            // Then: deleteByBookId must be called before insertAll
            val inOrder: InOrder = inOrder(mockChapterDao)
            inOrder.verify(mockChapterDao).deleteByBookId(testBookId)
            inOrder.verify(mockChapterDao).insertAll(any())
        }

    /**
     * TEST: loadChapterData uses library-specific server connection
     *
     * Verifies library-aware chapter loading (consistent with TrackRepository pattern).
     */
    @Test
    fun `loadChapterData uses library-specific server connection`() =
        runTest {
            // Given: Library-specific server connection
            val track =
                createMockTrack(
                    trackId = testTrackId,
                    libraryId = testLibraryId,
                )
            val connection = ServerConnection(testServerUrl, testToken)
            whenever(mockServerConnectionResolver.resolve(testLibraryId)).thenReturn(connection)
            whenever(mockScopedPlexServiceFactory.getOrCreateService(connection)).thenReturn(mockScopedService)

            val chapterResponse = createMockChapterResponse(2)
            whenever(mockScopedService.retrieveChapterInfo(471)).thenReturn(chapterResponse)

            // When: Loading chapters for a track in this library
            chapterRepository.loadChapterData(testBookId, isAudiobookCached = false, tracks = listOf(track))

            // Then: Should use library-specific connection
            verify(mockServerConnectionResolver).resolve(testLibraryId)
            verify(mockScopedPlexServiceFactory).getOrCreateService(connection)
            verify(mockScopedService).retrieveChapterInfo(471)

            // And: Should delete old chapters then insert new ones
            verify(mockChapterDao).deleteByBookId(testBookId)
            verify(mockChapterDao).insertAll(any())
        }

    /**
     * TEST: loadChapterData handles track without libraryId gracefully
     *
     * Ensures graceful degradation when track data is incomplete.
     */
    @Test
    fun `loadChapterData skips tracks without libraryId`() =
        runTest {
            // Given: Track with empty libraryId
            val trackWithoutLibrary =
                createMockTrack(
                    trackId = testTrackId,
                    libraryId = "", // Empty libraryId
                )

            // When: loadChapterData is called
            chapterRepository.loadChapterData(testBookId, isAudiobookCached = false, tracks = listOf(trackWithoutLibrary))

            // Then: Should still call deleteByBookId (delete-before-insert pattern)
            verify(mockChapterDao).deleteByBookId(testBookId)

            // But: Should not attempt to fetch chapters from network
            verify(mockServerConnectionResolver, times(0)).resolve(any())

            // And: Should insert empty list (no chapters fetched)
            verify(mockChapterDao).insertAll(eq(emptyList()))
        }

    /**
     * TEST: loadChapterData handles invalid server connection gracefully
     *
     * Verifies graceful handling when server connection cannot be resolved.
     */
    @Test
    fun `loadChapterData handles invalid server connection gracefully`() =
        runTest {
            // Given: Invalid server connection (missing URL or token)
            val track =
                createMockTrack(
                    trackId = testTrackId,
                    libraryId = testLibraryId,
                )
            val invalidConnection = ServerConnection(null, null)
            whenever(mockServerConnectionResolver.resolve(testLibraryId)).thenReturn(invalidConnection)

            // When: loadChapterData is called
            chapterRepository.loadChapterData(testBookId, isAudiobookCached = false, tracks = listOf(track))

            // Then: Should still delete old chapters
            verify(mockChapterDao).deleteByBookId(testBookId)

            // But: Should not create scoped service
            verify(mockScopedPlexServiceFactory, times(0)).getOrCreateService(any())

            // And: Should insert empty list
            verify(mockChapterDao).insertAll(eq(emptyList()))
        }

    // ===== Helper methods =====

    /**
     * Creates a mock MediaItemTrack for testing.
     */
    private fun createMockTrack(
        trackId: String,
        libraryId: String,
    ): MediaItemTrack {
        return MediaItemTrack(
            id = trackId,
            title = "Test Track",
            parentKey = testBookId,
            libraryId = libraryId,
            index = 1,
            discNumber = 1,
            duration = 180000L,
            album = "Test Album",
            artist = "Test Author",
        )
    }

    /**
     * Creates a mock PlexMediaContainerWrapper with chapters.
     */
    private fun createMockChapterResponse(chapterCount: Int): PlexMediaContainerWrapper {
        val chapters =
            (1..chapterCount).map { index ->
                PlexChapter(
                    id = 471L,
                    tag = "Chapter $index",
                    index = index.toLong(),
                    startTimeOffset = ((index - 1) * 1000).toLong(),
                    endTimeOffset = (index * 1000).toLong(),
                )
            }

        val directory =
            PlexDirectory(
                ratingKey = "471",
                title = "Test Track",
                plexChapters = chapters,
            )

        val container =
            PlexMediaContainer(
                size = 1L,
                totalSize = 1L,
                offset = 0L,
                metadata = listOf(directory),
            )

        return PlexMediaContainerWrapper(container)
    }
}
