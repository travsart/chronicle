package local.oss.chronicle.data.model

/**
 * Represents server connection details for a library.
 * Used by ServerConnectionResolver to route playback requests to the correct server.
 *
 * @property serverUrl The Plex server URL (e.g., "https://192.168.1.100:32400")
 * @property authToken The authentication token for this library's account
 */
data class ServerConnection(
    val serverUrl: String?,
    val authToken: String?,
)
