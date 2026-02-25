package local.oss.chronicle.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a media library within an account.
 *
 * ID format: "{provider}:library:{sectionId}"
 * Example: "plex:library:3"
 */
@Entity(
    tableName = "libraries",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["serverId"]),
        Index(value = ["isActive"]),
    ],
)
data class Library(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val serverId: String,
    val serverName: String,
    val name: String,
    /**
     * Library type (e.g., "artist" for Plex audiobooks, "audiobook" for Audiobookshelf)
     */
    val type: String,
    val lastSyncedAt: Long?,
    val itemCount: Int,
    /**
     * Whether this is the currently active library.
     * Only one library should be active at a time.
     */
    val isActive: Boolean,
    /**
     * The Plex server URL for this library (e.g., "https://192.168.1.100:32400").
     * Used for library-aware playback to route requests to the correct server.
     * Populated during library sync.
     */
    @ColumnInfo(name = "serverUrl")
    val serverUrl: String? = null,
    /**
     * The authentication token for this library's account.
     * Used for library-aware playback to authenticate requests correctly.
     * Populated during library sync.
     */
    @ColumnInfo(name = "authToken")
    val authToken: String? = null,
)
