package local.oss.chronicle.data.local

import androidx.lifecycle.LiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.oss.chronicle.data.model.*
import androidx.lifecycle.map
import local.oss.chronicle.data.sources.MediaSource
import local.oss.chronicle.data.sources.plex.PlexMediaService
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.ScopedPlexServiceFactory
import local.oss.chronicle.data.sources.plex.ServerConnectionResolver
import local.oss.chronicle.data.sources.plex.model.asAudiobooks
import local.oss.chronicle.data.sources.plex.model.getDuration
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A repository abstracting all [Audiobook]s from all [MediaSource]s */
interface IBookRepository {
    /** Return all [Audiobook]s in the DB, sorted by [Audiobook.titleSort] */
    fun getAllBooks(): LiveData<List<Audiobook>>

    /** Return all [AudiobookListItem]s in the DB - lightweight projection for list views */
    fun getAllBooksLightweight(): LiveData<List<AudiobookListItem>>

    suspend fun getAllBooksAsync(): List<Audiobook>

    /** Return all [AudiobookListItem]s async - lightweight projection for list views */
    suspend fun getAllBooksLightweightAsync(): List<AudiobookListItem>

    suspend fun getRandomBookAsync(): Audiobook

    /** Refreshes the data in the local database with elements from the network */
    suspend fun refreshData()

    /** Returns the number of books in the repository */
    suspend fun getBookCount(): Int

    /** Returns the number of books in a specific library */
    suspend fun getBookCountForLibrary(libraryId: String): Int

    /** Removes all books from the local database */
    suspend fun clear()

    /** Updates the book with information regarding the tracks contained within the book */
    suspend fun updateTrackData(
        bookId: String,
        bookProgress: Long,
        bookDuration: Long,
        trackCount: Int,
    )

    /**
     * Returns a [LiveData<Audiobook>] corresponding to an [Audiobook] with the [Audiobook.id]
     * equal to [id]
     */
    fun getAudiobook(id: String): LiveData<Audiobook?>

    suspend fun getAudiobookAsync(bookId: String): Audiobook?

    /**
     * Returns the [getBookCount] most recently added books in the local database, ordered by most
     * recently added to added the longest time ago
     */
    fun getRecentlyAdded(): LiveData<List<Audiobook>>

    suspend fun getRecentlyAddedAsync(): List<Audiobook>

    /**
     * Returns the [getBookCount] most recently added listened to books in the local database,
     * ordered from most recently listened to last listened book
     */
    fun getRecentlyListened(): LiveData<List<Audiobook>>

    /**
     * Returns the [getBookCount] most recently added listened to books in the local database,
     * ordered from most recently listened to last listened book
     */
    suspend fun getRecentlyListenedAsync(): List<Audiobook>

    /**
     * Update the [Audiobook.lastViewedAt] and [Audiobook.progress] fields to [currentTime] and
     * [progress], respectively for a book with id [bookId]
     */
    suspend fun updateProgress(
        bookId: String,
        currentTime: Long,
        progress: Long,
    )

    /**
     * Return a [LiveData<List<Audiobook>>] of all audiobooks containing [query] within their
     * [Audiobook.author] or [Audiobook.title] fields
     */
    fun search(query: String): LiveData<List<Audiobook>>

    /**
     * Return a [List<Audiobook>] of all audiobooks containing [query] within their
     * [Audiobook.author] or [Audiobook.title] fields
     */
    suspend fun searchAsync(query: String): List<Audiobook>

    /**
     * Return the [Audiobook] which has been listened to the most recently. Specifically, look for
     * the [Audiobook.lastViewedAt] field which is largest among all [Audiobook]s in the local DB
     */
    suspend fun getMostRecentlyPlayed(): Audiobook

    /**
     * Returns a [LiveData<List<Audiobook>>] with all [Audiobook]s in the local DB where
     * [Audiobook.isCached] == true.
     */
    fun getCachedAudiobooks(): LiveData<List<Audiobook>>

    /**
     * Returns a [List<Audiobook>] with all [Audiobook]s in the local DB where [Audiobook.isCached] == true.
     */
    suspend fun getCachedAudiobooksAsync(): List<Audiobook>

    /** Sets the [Audiobook.isCached] for all [Audiobook]s in the DB to false */
    suspend fun uncacheAll()

    /**
     * Loads m4b chapter data and any other audiobook details which are not loaded in by default
     * from the network and saves it to the DB if there are chapters found. Pass [forceNetwork]
     * to determine if network data (progress, metadata) ought to be preferred
     *
     * @return true if chapter data was found and added to db, otherwise false
     */
    suspend fun syncAudiobook(
        audiobook: Audiobook,
        tracks: List<MediaItemTrack>,
        forceNetwork: Boolean = false,
    ): Boolean

    /** Updates the column uniquely identified by [Audiobook.id] to the new [Audiobook] */
    suspend fun update(audiobook: Audiobook)

    /**
     * Updates the [Audiobook.isCached] column to be true for the [Audiobook] uniquely identified
     * by [Audiobook.id] == [bookId]
     */
    suspend fun updateCachedStatus(
        bookId: String,
        isCached: Boolean,
    )

    /** Sets the book's [Audiobook.progress] to 0 in the DB and the server */
    suspend fun setWatched(bookId: String)

    suspend fun setUnwatched(bookId: String)

    /** Loads an [Audiobook] in from the network */
    suspend fun fetchBookAsync(
        bookId: String,
        libraryId: String,
    ): Audiobook?

    suspend fun refreshDataPaginated()
}

@Singleton
class BookRepository
    @Inject
    constructor(
        private val bookDao: BookDao,
        private val prefsRepo: PrefsRepo,
        private val plexPrefsRepo: PlexPrefsRepo,
        private val plexMediaService: PlexMediaService,
        private val chapterRepository: IChapterRepository,
        private val chapterDao: ChapterDao,
        private val serverConnectionResolver: ServerConnectionResolver,
        private val scopedPlexServiceFactory: ScopedPlexServiceFactory,
    ) : IBookRepository {
        // TODO: observe prefsRepo.offlineMode?

        /**
         * Limits the number of elements returned in cases where it doesn't make sense to return all
         * elements in the database
         */
        private val limitReturnCount = 25

        override fun getAllBooks(): LiveData<List<Audiobook>> {
            // Use lightweight query and convert to Audiobook for backwards compatibility
            return bookDao.getAllRowsLightweight(prefsRepo.offlineMode).map { items ->
                items.toAudiobooks()
            }
        }
    
        override fun getAllBooksLightweight(): LiveData<List<AudiobookListItem>> {
            return bookDao.getAllRowsLightweight(prefsRepo.offlineMode)
        }

        override suspend fun getBookCount(): Int {
            return withContext(Dispatchers.IO) {
                bookDao.getBookCount()
            }
        }

        override suspend fun getBookCountForLibrary(libraryId: String): Int {
            return withContext(Dispatchers.IO) {
                bookDao.getBookCountForLibrary(libraryId)
            }
        }

        @Throws(Throwable::class)
        override suspend fun refreshData() {
            if (prefsRepo.offlineMode) {
                return
            }
            prefsRepo.lastRefreshTimeStamp = System.currentTimeMillis()
            val networkBooks: List<Audiobook> =
                withContext(Dispatchers.IO) {
                    try {
                        val library = plexPrefsRepo.library!!
                        val libraryId = "plex:library:${library.id}"
                        plexMediaService.retrieveAllAlbums(library.id).plexMediaContainer.asAudiobooks(libraryId)
                    } catch (t: Throwable) {
                        Timber.i("Failed to retrieve books: $t")
                        null
                    }
                } ?: return
            //    ^^^ quit on network failure- nothing below matters without new books from server

            val localBooks = withContext(Dispatchers.IO) { bookDao.getAudiobooks() }

            val mergedBooks =
                networkBooks.map { networkBook ->
                    val localBook = localBooks.find { it.id == networkBook.id }
                    if (localBook != null) {
                        // [Audiobook.merge] chooses fields depending on [Audiobook.lastViewedAt]
                        return@map Audiobook.merge(network = networkBook, local = localBook)
                    } else {
                        return@map networkBook
                    }
                }

            // remove books which have been deleted from server (only from current library)
            val currentLibraryId = plexPrefsRepo.library?.let { "plex:library:${it.id}" } ?: return
            val networkIds = networkBooks.map { it.id }
            val removedFromNetwork =
                localBooks.filter { localBook ->
                    localBook.libraryId == currentLibraryId && !networkIds.contains(localBook.id)
                }

            Timber.i("Removed from network: ${removedFromNetwork.map { it.title }}")
            withContext(Dispatchers.IO) {
                val removed = bookDao.removeAll(removedFromNetwork.map { it.id.toString() })
                Timber.i("Removed $removed items from DB")

                Timber.i("Loaded books: $mergedBooks")
                bookDao.insertAll(mergedBooks)
            }
        }

        @Throws(Throwable::class)
        override suspend fun refreshDataPaginated() {
            if (prefsRepo.offlineMode) {
                return
            }

            prefsRepo.lastRefreshTimeStamp = System.currentTimeMillis()
            val networkBooks: MutableList<Audiobook> = mutableListOf()
            withContext(Dispatchers.IO) {
                try {
                    val library = plexPrefsRepo.library ?: return@withContext
                    val libraryIdNumeric = library.id
                    val libraryId = "plex:library:$libraryIdNumeric"
                    var booksLeft = 1L
                    // Maximum number of pages of data we fetch. Failsafe in case of bad data from the
                    // server since we don't want infinite loops. This limits us to a maximum 1,000,000
                    // tracks for now
                    val maxIterations = 5000
                    var i = 0
                    while (booksLeft > 0 && i < maxIterations) {
                        val response =
                            plexMediaService
                                .retrieveAlbumPage(libraryIdNumeric, i * 100)
                                .plexMediaContainer
                        booksLeft = response.totalSize - (response.offset + response.size)
                        networkBooks.addAll(response.asAudiobooks(libraryId))
                        i++
                    }
                } catch (t: Throwable) {
                    Timber.i("Failed to retrieve books: $t")
                }
            }
            //    ^^^ quit on network failure- nothing below matters without new books from server

            val localBooks = withContext(Dispatchers.IO) { bookDao.getAudiobooks() }

            val mergedBooks =
                networkBooks.map { networkBook ->
                    val localBook = localBooks.find { it.id == networkBook.id }
                    if (localBook != null) {
                        // [Audiobook.merge] chooses fields depending on [Audiobook.lastViewedAt]
                        return@map Audiobook.merge(network = networkBook, local = localBook)
                    } else {
                        return@map networkBook
                    }
                }

            // remove books which have been deleted from server (only from current library)
            val currentLibraryId = plexPrefsRepo.library?.let { "plex:library:${it.id}" } ?: return
            val networkIds = networkBooks.map { it.id }
            val removedFromNetwork =
                localBooks.filter { localBook ->
                    localBook.libraryId == currentLibraryId && !networkIds.contains(localBook.id)
                }

            Timber.i("Removed from network: ${removedFromNetwork.map { it.title }}")
            withContext(Dispatchers.IO) {
                val removed = bookDao.removeAll(removedFromNetwork.map { it.id.toString() })
                Timber.i("Removed $removed items from DB")

                Timber.i("Loaded books: $mergedBooks")
                bookDao.insertAll(mergedBooks)
            }
        }

        override suspend fun clear() {
            withContext(Dispatchers.IO) {
                bookDao.clear()
            }
        }

        override suspend fun updateTrackData(
            bookId: String,
            bookProgress: Long,
            bookDuration: Long,
            trackCount: Int,
        ) {
            withContext(Dispatchers.IO) {
                bookDao.updateTrackData(bookId, bookProgress, bookDuration, trackCount)
            }
        }

        override fun getAudiobook(id: String): LiveData<Audiobook?> {
            return bookDao.getAudiobook(id, prefsRepo.offlineMode)
        }

        override fun getRecentlyAdded(): LiveData<List<Audiobook>> {
            return bookDao.getRecentlyAdded(limitReturnCount, prefsRepo.offlineMode)
        }

        override suspend fun getRecentlyAddedAsync(): List<Audiobook> {
            return withContext(Dispatchers.IO) {
                bookDao.getRecentlyAddedAsync(limitReturnCount, prefsRepo.offlineMode)
            }
        }

        override fun getRecentlyListened(): LiveData<List<Audiobook>> {
            return bookDao.getRecentlyListened(limitReturnCount, prefsRepo.offlineMode)
        }

        override suspend fun getRecentlyListenedAsync(): List<Audiobook> {
            return withContext(Dispatchers.IO) {
                bookDao.getRecentlyListenedAsync(limitReturnCount, prefsRepo.offlineMode)
            }
        }

        override suspend fun updateProgress(
            bookId: String,
            currentTime: Long,
            progress: Long,
        ) {
            withContext(Dispatchers.IO) {
                bookDao.updateProgress(bookId, currentTime, progress)
            }
        }

        override suspend fun searchAsync(query: String): List<Audiobook> {
            return withContext(Dispatchers.IO) {
                bookDao.searchAsync("%$query%", prefsRepo.offlineMode)
            }
        }

        override fun search(query: String): LiveData<List<Audiobook>> {
            return bookDao.search("%$query%", prefsRepo.offlineMode)
        }

        override suspend fun update(audiobook: Audiobook) {
            withContext(Dispatchers.IO) {
                // set the chapters stored in the db to also be cached
                bookDao.update(audiobook)
            }
        }

        override suspend fun updateCachedStatus(
            bookId: String,
            isCached: Boolean,
        ) {
            withContext(Dispatchers.IO) {
                // set the chapters stored in the db to also be cached
                bookDao.updateCachedStatus(bookId, isCached)
                val audiobook = bookDao.getAudiobookAsync(bookId)
                audiobook?.let { book ->
                    bookDao.update(
                        book.copy(chapters = book.chapters.map { it.copy(downloaded = isCached) }),
                    )
                }
            }
        }

        override suspend fun setWatched(bookId: String) {
            withContext(Dispatchers.IO) {
                try {
                    // Extract numeric ID for Plex API call
                    val numericId = bookId.removePrefix("plex:").toIntOrNull() ?: return@withContext

                    // Get audiobook to determine library ID
                    val audiobook = bookDao.getAudiobookAsync(bookId)
                    if (audiobook == null) {
                        Timber.w("Cannot set watched status - audiobook not found: $bookId")
                        return@withContext
                    }

                    // Resolve library-specific server connection
                    val connection = serverConnectionResolver.resolve(audiobook.libraryId)
                    val scopedService = scopedPlexServiceFactory.getOrCreateService(connection)

                    // Use scoped service to call the correct server
                    scopedService.watched(numericId.toString())
                    bookDao.setWatched(bookId)
                    bookDao.resetBookProgress(bookId)
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to update watched status for bookId=$bookId")
                }
            }
        }

        override suspend fun setUnwatched(bookId: String) {
            withContext(Dispatchers.IO) {
                try {
                    // Extract numeric ID for Plex API call
                    val numericId = bookId.removePrefix("plex:").toIntOrNull() ?: return@withContext

                    // Get audiobook to determine library ID
                    val audiobook = bookDao.getAudiobookAsync(bookId)
                    if (audiobook == null) {
                        Timber.w("Cannot set unwatched status - audiobook not found: $bookId")
                        return@withContext
                    }

                    // Resolve library-specific server connection
                    val connection = serverConnectionResolver.resolve(audiobook.libraryId)
                    val scopedService = scopedPlexServiceFactory.getOrCreateService(connection)

                    // Use scoped service to call the correct server
                    scopedService.unwatched(numericId.toString())
                    bookDao.setUnwatched(bookId)
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to update unwatched status for bookId=$bookId")
                }
            }
        }

        override suspend fun getMostRecentlyPlayed(): Audiobook {
            return bookDao.getMostRecent() ?: EMPTY_AUDIOBOOK
        }

        override suspend fun getAudiobookAsync(bookId: String): Audiobook? {
            return withContext(Dispatchers.IO) {
                bookDao.getAudiobookAsync(bookId)
            }
        }

        override fun getCachedAudiobooks(): LiveData<List<Audiobook>> {
            return bookDao.getCachedAudiobooks()
        }

        override suspend fun getCachedAudiobooksAsync(): List<Audiobook> {
            return withContext(Dispatchers.IO) {
                bookDao.getCachedAudiobooksAsync()
            }
        }

        override suspend fun uncacheAll() {
            withContext(Dispatchers.IO) {
                bookDao.uncacheAll()
            }
        }

        override suspend fun getAllBooksAsync(): List<Audiobook> {
            // Use lightweight query and convert to Audiobook for backwards compatibility
            return withContext(Dispatchers.IO) {
                bookDao.getAllBooksLightweightAsync(prefsRepo.offlineMode).toAudiobooks()
            }
        }
    
        override suspend fun getAllBooksLightweightAsync(): List<AudiobookListItem> {
            return withContext(Dispatchers.IO) {
                bookDao.getAllBooksLightweightAsync(prefsRepo.offlineMode)
            }
        }

        override suspend fun getRandomBookAsync(): Audiobook {
            return withContext(Dispatchers.IO) {
                bookDao.getRandomBookAsync() ?: EMPTY_AUDIOBOOK
            }
        }

        override suspend fun syncAudiobook(
            audiobook: Audiobook,
            tracks: List<MediaItemTrack>,
            forceNetwork: Boolean,
        ): Boolean {
            Timber.i(
                "Loading chapter data. Book ID is ${audiobook.id}, it is ${
                    if (audiobook.isCached) "cached" else "uncached"
                }, tracks are $tracks",
            )
            withContext(Dispatchers.IO) {
                // Delegate chapter loading to ChapterRepository (library-aware)
                try {
                    chapterRepository.loadChapterData(audiobook.id, audiobook.isCached, tracks)
                } catch (t: Throwable) {
                    Timber.e("Failed to load chapters: $t")
                    return@withContext false
                }

                // Read chapters back from database, filtering by track IDs
                val trackIds = tracks.map { it.id }.toSet()
                val chapters: List<Chapter> =
                    try {
                        chapterDao.getChapters().filter { chapter ->
                            trackIds.contains(chapter.trackId)
                        }.sorted()
                    } catch (t: Throwable) {
                        Timber.e("Failed to retrieve chapters from database: $t")
                        emptyList()
                    }

                Timber.i("Loaded chapters: ${chapters.map { "[${it.index}/${it.discNumber}]" }}")

                val networkBook =
                    try {
                        val retrievedBook = fetchBookAsync(audiobook.id, audiobook.libraryId)
                        retrievedBook ?: return@withContext false
                    } catch (t: Throwable) {
                        Timber.e("Failed to load audiobook update: $t")
                        return@withContext false
                    }

                val merged =
                    Audiobook.merge(
                        network = networkBook,
                        local = audiobook,
                        forceNetwork = forceNetwork,
                    ).copy(
                        progress = tracks.getProgress(),
                        duration = tracks.getDuration(),
                        chapters = chapters,
                    )
                bookDao.update(merged)
            }
            return true
        }

        override suspend fun fetchBookAsync(
            bookId: String,
            libraryId: String,
        ): Audiobook? =
            withContext(Dispatchers.IO) {
                try {
                    // Extract numeric ID for Plex API call
                    val numericId = bookId.removePrefix("plex:").toIntOrNull() ?: return@withContext null

                    // Resolve library-specific server connection
                    val connection = serverConnectionResolver.resolve(libraryId)
                    val scopedService = scopedPlexServiceFactory.getOrCreateService(connection)

                    Timber.d("Fetching book metadata for bookId=$bookId from libraryId=$libraryId")

                    // Use scoped service to call the correct server
                    scopedService.retrieveAlbum(numericId)
                        .plexMediaContainer
                        .asAudiobooks(libraryId) // Tag with the correct libraryId
                        .firstOrNull()
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to fetch book async for bookId=$bookId, libraryId=$libraryId")
                    null
                }
            }

        suspend fun deleteByLibraryId(libraryId: String) {
            withContext(Dispatchers.IO) {
                bookDao.deleteByLibraryId(libraryId)
            }
        }
    }
