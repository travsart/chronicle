package local.oss.chronicle.features.auth

import local.oss.chronicle.data.sources.plex.APP_NAME
import java.net.URLEncoder

/**
 * Utility object for building Plex OAuth URLs for Chrome Custom Tabs.
 *
 * Constructs the OAuth URL with proper encoding and required parameters including:
 * - PIN code for authentication
 * - Client identifier
 * - Forward URL for post-authentication redirect
 * - Device context (product, platform, environment, layout, device)
 *
 * **Critical:** Platform is set to "Web" instead of "Android" to allow social login
 * (Google, Facebook) which is blocked in embedded WebViews.
 */
object PlexAuthUrlBuilder {
    private const val FORWARD_URL = "https://auth.chronicleapp.net/callback"
    private const val AUTH_BASE_URL = "https://app.plex.tv/auth#"
    private const val PLATFORM = "Web" // Critical: Must be "Web" for social login support

    /**
     * Builds a properly encoded OAuth URL for Chrome Custom Tabs.
     *
     * @param clientId The Plex client identifier (UUID)
     * @param pinCode The PIN code obtained from POST /pins endpoint
     * @return Fully constructed and URL-encoded OAuth URL
     */
    fun buildOAuthUrl(
        clientId: String,
        pinCode: String,
    ): String {
        val params = buildMap<String, String> {
            put("code", pinCode)
            put("clientID", clientId)
            put("forwardUrl", FORWARD_URL)
            put("context[device][product]", APP_NAME)
            put("context[device][platform]", PLATFORM)
            put("context[device][environment]", "bundled")
            put("context[device][layout]", "desktop")
            put("context[device][device]", APP_NAME)
        }

        return buildString {
            append(AUTH_BASE_URL)
            append("?")
            append(
                params.entries.joinToString("&") { (key, value) ->
                    "${urlEncode(key)}=${urlEncode(value)}"
                },
            )
        }
    }

    /**
     * URL-encodes a string using UTF-8 encoding.
     * Uses java.net.URLEncoder for compatibility with unit tests.
     */
    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }
}
