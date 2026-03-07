package local.oss.chronicle.data.local

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.model.AudiobookListItem
import local.oss.chronicle.data.sources.plex.PlexMediaService
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.ScopedPlexServiceFactory
import local.oss.chronicle.data.sources.plex.ServerConnectionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for lightweight query methods in BookRepository.
 * Verifies that the repository exposes lightweight queries for memory-efficient list loading.
 */
@ExperimentalCoroutinesApi
class BookRepositoryLightweightTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var bookDao: BookDao
    private lateinit var prefsRepo: PrefsRepo
    private lateinit var plexPrefsRepo: PlexPrefsRepo
    private lateinit var plexMediaService: PlexMediaService
    private lateinit var chapterRepository: IChapterRepository
    private lateinit var chapterDao: ChapterDao
    private lateinit var serverConnectionResolver: ServerConnectionResolver
    private lateinit var scopedPlexServiceFactory: ScopedPlexServiceFactory
    private lateinit var bookRepository: BookRepository

    @Before
    fun setup() {
        bookDao = mockk(relaxed = true)
        prefsRepo = mockk(relaxed = true)
        plexPrefsRepo = mockk(relaxed = true)
        plexMediaService = mockk(relaxed = true)
        chapterRepository = mockk(relaxed = true)
        chapterDao = mockk(relaxed = true)
        serverConnectionResolver = mockk(relaxed = true)
        scopedPlexServiceFactory = mockk(relaxed = true)

        bookRepository = BookRepository(
            bookDao = bookDao,
            prefsRepo = prefsRepo,
            plexPrefsRepo = plexPrefsRepo,
            plexMediaService = plexMediaService,
            chapterRepository = chapterRepository,
            chapterDao = chapterDao,
            serverConnectionResolver = serverConnectionResolver,
            scopedPlexServiceFactory = scopedPlexServiceFactory
        )
    }

    @Test
    fun `getAllBooksLightweight returns LiveData from DAO`() {
        // Arrange
        val mockLiveData = MutableLiveData<List<AudiobookListItem>>()
        every { prefsRepo.offlineMode } returns false
        every { bookDao.getAllRowsLightweight(any()) } returns mockLiveData

        // Act
        val result = bookRepository.getAllBooksLightweight()

        // Assert
        assertNotNull(result)
        verify { bookDao.getAllRowsLightweight(false) }
    }

    @Test
    fun `getAllBooksLightweight respects offline mode`() {
        // Arrange
        val mockLiveData = MutableLiveData<List<AudiobookListItem>>()
        every { prefsRepo.offlineMode } returns true
        every { bookDao.getAllRowsLightweight(any()) } returns mockLiveData

        // Act
        val result = bookRepository.getAllBooksLightweight()

        // Assert
        assertNotNull(result)
        verify { bookDao.getAllRowsLightweight(true) }
    }

    @Test
    fun `getAllBooksLightweightAsync returns list from DAO`() = runTest {
        // Arrange
        val expectedList = listOf(
            AudiobookListItem(
                id = "plex:1",
                libraryId = "plex:library:1",
                source = 1L,
                title = "Book 1",
                titleSort = "Book 1",
                author = "Author 1",
                thumb = "/thumb1",
                duration = 1000L,
                progress = 0L,
                isCached = false,
                lastViewedAt = 0L,
                viewCount = 0L,
                addedAt = 1234567890L,
                year = 2024,
                viewedLeafCount = 0L
            )
        )
        every { prefsRepo.offlineMode } returns false
        every { bookDao.getAllBooksLightweightAsync(any()) } returns expectedList

        // Act
        val result = bookRepository.getAllBooksLightweightAsync()

        // Assert
        assertEquals(expectedList, result)
        verify { bookDao.getAllBooksLightweightAsync(false) }
    }
}
