package local.oss.chronicle.data.model

import androidx.room.ColumnInfo
import local.oss.chronicle.data.sources.MediaSource

/**
 * Lightweight projection of [Audiobook] containing only fields needed for list display.
 * This excludes heavy fields like [Audiobook.summary], [Audiobook.genre], and [Audiobook.chapters]
 * to reduce memory consumption when loading large libraries.
 *
 * Use this for list views (Library, Home, Collections, Search). Use full [Audiobook] for detail view.
 */
data class AudiobookListItem(
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "libraryId")
    val libraryId: String,

    @ColumnInfo(name = "source")
    val source: Long,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "titleSort")
    val titleSort: String,

    @ColumnInfo(name = "author")
    val author: String,

    @ColumnInfo(name = "thumb")
    val thumb: String,

    @ColumnInfo(name = "duration")
    val duration: Long,

    @ColumnInfo(name = "progress")
    val progress: Long,

    @ColumnInfo(name = "isCached")
    val isCached: Boolean,

    @ColumnInfo(name = "lastViewedAt")
    val lastViewedAt: Long,

    @ColumnInfo(name = "viewCount")
    val viewCount: Long,

    @ColumnInfo(name = "addedAt")
    val addedAt: Long,

    @ColumnInfo(name = "year")
    val year: Int,

    @ColumnInfo(name = "viewedLeafCount")
    val viewedLeafCount: Long
) {
    /**
     * Converts this lightweight item to a full [Audiobook] with default values for heavy fields.
     * Use this when the UI expects an Audiobook but we loaded lightweight data.
     */
    fun toAudiobook(): Audiobook {
        return Audiobook(
            id = id,
            libraryId = libraryId,
            source = source,
            title = title,
            titleSort = titleSort,
            author = author,
            thumb = thumb,
            duration = duration,
            progress = progress,
            isCached = isCached,
            lastViewedAt = lastViewedAt,
            viewCount = viewCount,
            addedAt = addedAt,
            year = year,
            viewedLeafCount = viewedLeafCount,
            // Heavy fields get default values - not used in list views
            parentId = -1,
            genre = "",
            summary = "",
            updatedAt = 0L,
            favorited = false,
            leafCount = 0L,
            chapters = emptyList()
        )
    }
}

/**
 * Extension function to convert a list of [AudiobookListItem]s to [Audiobook]s.
 */
fun List<AudiobookListItem>.toAudiobooks(): List<Audiobook> {
    return this.map { it.toAudiobook() }
}
