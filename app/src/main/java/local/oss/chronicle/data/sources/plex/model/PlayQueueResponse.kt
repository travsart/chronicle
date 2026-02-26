package local.oss.chronicle.data.sources.plex.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from POST /playQueues containing play queue item IDs.
 *
 * Plex uses these IDs to correlate timeline updates with specific tracks
 * in a play queue, which enables dashboard activity reporting.
 *
 * Example response:
 * ```json
 * {
 *   "MediaContainer": {
 *     "playQueueID": 100,
 *     "playQueueSelectedItemID": 1001,
 *     "Metadata": [
 *       {
 *         "ratingKey": "12345",
 *         "playQueueItemID": 1001,
 *         "title": "Chapter 1"
 *       }
 *     ]
 *   }
 * }
 * ```
 */
@JsonClass(generateAdapter = true)
data class PlayQueueResponseWrapper(
    @Json(name = "MediaContainer") val mediaContainer: PlayQueueMediaContainer?,
)

@JsonClass(generateAdapter = true)
data class PlayQueueMediaContainer(
    val playQueueID: Long = -1,
    val playQueueSelectedItemID: Long = -1,
    @Json(name = "Metadata")
    val metadata: List<PlayQueueItem>? = null,
)

@JsonClass(generateAdapter = true)
data class PlayQueueItem(
    val ratingKey: String = "",
    val playQueueItemID: Long = -1,
    val title: String = "",
)

/**
 * Maps play queue response to a map of ratingKey -> playQueueItemID.
 *
 * @return Map of track ID (e.g., "plex:12345") to playQueueItemID
 */
fun PlayQueueResponseWrapper.toPlayQueueItemMap(): Map<String, Long> {
    val metadata = mediaContainer?.metadata ?: return emptyMap()
    return metadata.associate { item ->
        "plex:${item.ratingKey}" to item.playQueueItemID
    }
}
