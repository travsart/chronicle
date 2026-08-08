package local.oss.chronicle.data.sources.plex

/**
 * Plex expects boolean-like query flags on some endpoints as integer values (0/1), not Kotlin
 * booleans serialized as "true"/"false".
 */
fun Boolean.toPlexQueryFlag(): Int = if (this) 1 else 0

/**
 * Creates a URI uniquely identifying a media item with id [mediaId ]on a server with machine
 * identifier [machineIdentifier]
 */
fun getMediaItemUri(
    machineIdentifier: String,
    mediaId: String,
): String {
    return "server://$machineIdentifier/com.plexapp.plugins.library/library/metadata/$mediaId"
}
