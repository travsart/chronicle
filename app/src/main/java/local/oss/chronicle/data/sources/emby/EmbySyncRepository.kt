package local.oss.chronicle.data.sources.emby

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.oss.chronicle.data.local.AccountRepository
import local.oss.chronicle.data.local.BookDao
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.local.TrackDao
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.features.account.CredentialManager
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbySyncRepository
    @Inject
    constructor(
        private val apiFactory: EmbyApiFactory,
        private val accountRepository: AccountRepository,
        private val libraryRepository: LibraryRepository,
        private val credentialManager: CredentialManager,
        private val bookDao: BookDao,
        private val trackDao: TrackDao,
    ) {
        suspend fun syncLibrary(library: Library) = withContext(Dispatchers.IO) {
            val account = accountRepository.getAccountById(library.accountId)
                ?: throw IllegalStateException("Emby account not found")
            val accessToken = credentialManager.getCredentials(account.id)
                ?: throw IllegalStateException("Emby credentials are missing")
            val userId = account.id.substringAfterLast(':')
            val viewId = library.id.substringAfterLast(':')
            val service = apiFactory.create(library.serverUrl ?: throw IllegalStateException("Emby server URL is missing"))

            val items = mutableListOf<EmbyItem>()
            var startIndex = 0
            var totalRecords: Int
            do {
                val page =
                    service.getAudioItems(
                        userId = userId,
                        parentId = viewId,
                        startIndex = startIndex,
                        accessToken = accessToken,
                    )
                items.addAll(page.items)
                totalRecords = page.totalRecordCount
                startIndex += page.items.size
            } while (page.items.isNotEmpty() && startIndex < totalRecords)

            val localBooks = bookDao.getAudiobooks().filter { it.libraryId == library.id }.associateBy { it.id }
            val localTracks = trackDao.getAllTracksAsync().filter { it.libraryId == library.id }.associateBy { it.id }
            val networkTracks = items.mapIndexed { index, item -> toTrack(item, library.id, index) }
            val tracksByBook = networkTracks.groupBy { it.parentKey }
            val networkBooks =
                tracksByBook.map { (bookId, tracks) ->
                    val representative = items.first { "emby:${it.id}" == tracks.first().id }
                    val networkBook = representative.toAudiobook(bookId, library.id, tracks)
                    localBooks[bookId]?.let { Audiobook.merge(networkBook, it) } ?: networkBook
                }
            val mergedTracks =
                networkTracks.map { track ->
                    localTracks[track.id]?.let { MediaItemTrack.merge(track, it) } ?: track
                }

            bookDao.deleteByLibraryId(library.id)
            bookDao.insertAll(networkBooks)
            trackDao.deleteByLibraryId(library.id)
            trackDao.insertAll(mergedTracks)
            libraryRepository.updateSyncTimestamp(library.id, System.currentTimeMillis())
            libraryRepository.updateItemCount(library.id, networkBooks.size)
        }

        companion object {
            const val MEDIA_SOURCE_ID = 1L

            fun toTrack(
                item: EmbyItem,
                libraryId: String,
                fallbackIndex: Int,
            ): MediaItemTrack = item.asTrack(libraryId, fallbackIndex)

            private fun EmbyItem.asTrack(
                libraryId: String,
                fallbackIndex: Int,
            ): MediaItemTrack {
                val albumKey =
                    albumId
                        ?: album?.takeIf { it.isNotBlank() }?.let { "album:${it.lowercase()}|${albumArtist.orEmpty().lowercase()}" }
                        ?: "item:$id"
                val bookId = "emby:$albumKey"
                val imageItemId = primaryImageItemId ?: albumId ?: id
                val imageTag = primaryImageTag?.let { "?tag=$it" }.orEmpty()
                return MediaItemTrack(
                    id = "emby:$id",
                    parentKey = bookId,
                    libraryId = libraryId,
                    title = name,
                    thumb = "Items/$imageItemId/Images/Primary$imageTag",
                    index = indexNumber ?: fallbackIndex + 1,
                    discNumber = parentIndexNumber ?: 1,
                    duration = (runTimeTicks ?: 0L) / 10_000L,
                    media = "Audio/$id/stream?static=true",
                    album = album ?: name,
                    artist = albumArtist ?: artists.firstOrNull().orEmpty(),
                    genre = genres.joinToString(", "),
                    artwork = "Items/$imageItemId/Images/Primary$imageTag",
                    viewCount = userData?.playCount?.toLong() ?: 0L,
                    progress = (userData?.playbackPositionTicks ?: 0L) / 10_000L,
                    lastViewedAt = userData?.lastPlayedDate.toEpochMillis(),
                    updatedAt = dateCreated.toEpochMillis(),
                    size = size ?: 0L,
                    source = MEDIA_SOURCE_ID,
                )
            }

            private fun EmbyItem.toAudiobook(
                bookId: String,
                libraryId: String,
                tracks: List<MediaItemTrack>,
            ) = Audiobook(
                id = bookId,
                libraryId = libraryId,
                source = MEDIA_SOURCE_ID,
                title = album ?: name,
                titleSort = sortName ?: album ?: name,
                author = albumArtist ?: artists.firstOrNull().orEmpty(),
                thumb = tracks.firstNotNullOfOrNull { it.thumb } ?: "",
                genre = genres.joinToString(", "),
                summary = overview.orEmpty(),
                year = productionYear ?: 0,
                addedAt = dateCreated.toEpochMillis(),
                updatedAt = dateCreated.toEpochMillis(),
                lastViewedAt = tracks.maxOfOrNull { it.lastViewedAt } ?: 0L,
                duration = tracks.sumOf { it.duration },
                progress = tracks.sumOf { it.progress },
                viewedLeafCount = tracks.count { it.viewCount > 0L }.toLong(),
                leafCount = tracks.size.toLong(),
                viewCount = tracks.sumOf { it.viewCount },
            )

            private fun String?.toEpochMillis(): Long =
                this?.let { value ->
                    runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
                } ?: 0L
        }
    }