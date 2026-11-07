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
import timber.log.Timber
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
        suspend fun syncLibrary(library: Library) =
            withContext(Dispatchers.IO) {
                val account =
                    accountRepository.getAccountById(library.accountId)
                        ?: throw IllegalStateException("Emby account not found")
                val accessToken =
                    credentialManager.getCredentials(account.id)
                        ?: throw IllegalStateException("Emby credentials are missing")
                val userId = account.id.substringAfterLast(':')
                val viewId = library.id.substringAfterLast(':')
                val serverUrl = library.serverUrl ?: throw IllegalStateException("Emby server URL is missing")
                val service = apiFactory.create(serverUrl)

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

                Timber.i(
                    "Emby returned ${items.size} audio items for library '${library.name}' " +
                        "(reported total: $totalRecords)",
                )

                val localBooks =
                    bookDao.getAudiobooks()
                        .filter { it.libraryId == library.id }
                        .associateBy { it.id }
                val localTracks =
                    trackDao.getAllTracksAsync()
                        .filter { it.libraryId == library.id }
                        .associateBy { it.id }
                val networkTracks =
                    items.mapIndexed { index, item -> toTrack(item, library.id, index) }
                val tracksByBook = networkTracks.groupBy { it.parentKey }
                val mergedTracks =
                    networkTracks.map { track ->
                        localTracks[track.id]?.let { MediaItemTrack.merge(track, it) } ?: track
                    }
                val mergedTracksByBook = mergedTracks.groupBy { it.parentKey }
                val itemsById = items.associateBy { "emby:${it.id}" }
                val networkBooks =
                    tracksByBook.map { (bookId, tracks) ->
                        val representative = itemsById.getValue(tracks.first().id)
                        val networkBook = representative.toAudiobook(bookId, library.id, tracks)
                        val merged = localBooks[bookId]?.let { Audiobook.merge(networkBook, it) } ?: networkBook
                        // Audiobook.merge keeps the local book progress even when the server copy is
                        // newer. Derive it from the merged tracks instead, which already apply the
                        // per-track server/local conflict rule, so book and track progress agree.
                        val bookTracks = mergedTracksByBook[bookId].orEmpty()
                        merged.copy(
                            progress = bookTracks.sumOf { it.progress },
                            lastViewedAt = maxOf(merged.lastViewedAt, bookTracks.maxOfOrNull { it.lastViewedAt } ?: 0L),
                        )
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
                val imagePath = primaryImagePath()
                return MediaItemTrack(
                    id = "emby:$id",
                    parentKey = bookId,
                    libraryId = libraryId,
                    title = name,
                    thumb = imagePath,
                    index = indexNumber ?: fallbackIndex + 1,
                    discNumber = parentIndexNumber ?: 1,
                    duration = (runTimeTicks ?: 0L) / 10_000L,
                    media = "Audio/$id/stream?static=true",
                    album = album ?: name,
                    artist = albumArtist ?: artists.firstOrNull().orEmpty(),
                    genre = genres.joinToString(", "),
                    artwork = imagePath,
                    viewCount = userData?.playCount?.toLong() ?: 0L,
                    progress = (userData?.playbackPositionTicks ?: 0L) / 10_000L,
                    lastViewedAt = userData?.lastPlayedDate.toEpochMillis(),
                    updatedAt = dateCreated.toEpochMillis(),
                    size = size ?: 0L,
                    source = MEDIA_SOURCE_ID,
                )
            }

            /**
             * Returns the relative Primary image path for this item, or "" when Emby reports no
             * image anywhere we can reach. Requesting an image without a tag returns 404 and was
             * retried on every bind, so an empty path (which resolves to no URI) is used instead.
             * Order: the track's own image, the album's image, then the parent folder's image.
             */
            internal fun EmbyItem.primaryImagePath(): String {
                val ownTag = primaryImageTag ?: imageTags["Primary"]
                val (ownerId, tag) =
                    when {
                        ownTag != null -> (primaryImageItemId ?: id) to ownTag
                        albumPrimaryImageTag != null && albumId != null -> albumId to albumPrimaryImageTag
                        parentPrimaryImageTag != null && parentPrimaryImageItemId != null ->
                            parentPrimaryImageItemId to parentPrimaryImageTag
                        else -> return ""
                    }
                return "Items/$ownerId/Images/Primary?tag=$tag"
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
                thumb = tracks.firstOrNull { !it.thumb.isNullOrBlank() }?.thumb.orEmpty(),
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
