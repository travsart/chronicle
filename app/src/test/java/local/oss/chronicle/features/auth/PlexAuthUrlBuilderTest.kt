package local.oss.chronicle.features.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlexAuthUrlBuilder].
 *
 * Tests URL construction with various inputs, verifying:
 * - Proper encoding of parameters
 * - Inclusion of forwardUrl
 * - Platform set to "Web"
 * - All required parameters present
 */
class PlexAuthUrlBuilderTest {
    @Test
    fun buildOAuthUrl_containsBaseUrl() {
        val url =
            PlexAuthUrlBuilder.buildOAuthUrl(
                clientId = "test-client-id",
                pinCode = "test-pin-code",
            )

        assertTrue("URL should start with auth base URL", url.startsWith("https://app.plex.tv/auth#?"))
    }

    @Test
    fun buildOAuthUrl_containsClientId() {
        val clientId = "test-client-123"
        val url =
            PlexAuthUrlBuilder.buildOAuthUrl(
                clientId = clientId,
                pinCode = "test-pin",
            )

        assertTrue(
            "URL should contain clientID parameter",
            url.contains("clientID=$clientId"),
        )
    }

    @Test
    fun buildOAuthUrl_containsPinCode() {
        val pinCode = "ABC123"
        val url =
            PlexAuthUrlBuilder.buildOAuthUrl(
                clientId = "test-client",
                pinCode = pinCode,
            )

        assertTrue(
            "URL should contain code parameter",
            url.contains("code=$pinCode"),
        )
    }

    @Test
    fun buildOAuthUrl_containsForwardUrl() {
        val url =
            PlexAuthUrlBuilder.buildOAuthUrl(
                clientId = "test-client",
                pinCode = "test-pin",
            )

        assertTrue(
            "URL should contain forwardUrl parameter",
            url.contains("forwardUrl=https%3A%2F%2Fauth.chronicleapp.net%2Fcallback"),
        )
    }

    @Test
    fun buildOAuthUrl_platformIsWeb() {
        val url =
            PlexAuthUrlBuilder.buildOAuthUrl(
                clientId = "test-client",
                pinCode = "test-pin",
            )

        assertTrue(
            "Platform should be set to Web (not Android) for social login support",
            url.contains("context%5Bdevice%5D%5Bplatform%5D=Web"),
        )
    }

    @Test
    fun buildOAuthUrl_containsAllRequiredContextParameters() {
        val url =
            PlexAuthUrlBuilder.buildOAuthUrl(
                clientId = "test-client",
                pinCode = "test-pin",
            )

        // Verify all context parameters are present
        assertTrue(
            "URL should contain product context",
            url.contains("context%5Bdevice%5D%5Bproduct%5D=Chronicle"),
        )
        assertTrue(
            "URL should contain platform context",
            url.contains("context%5Bdevice%5D%5Bplatform%5D=Web"),
        )
        assertTrue(
            "URL should contain environment context",
            url.contains("context%5Bdevice%5D%5Benvironment%5D=bundled"),
        )
        assertTrue(
            "URL should contain layout context",
            url.contains("context%5Bdevice%5D%5Blayout%5D=desktop"),
        )
        assertTrue(
            "URL should contain device context",
            url.contains("context%5Bdevice%5D%5Bdevice%5D=Chronicle"),
        )
    }

    @Test
    fun buildOAuthUrl_encodesSpecialCharacters() {
        // Test with special characters in parameters
        val clientId = "client-with-special-&=?"
        val pinCode = "pin-with-#-and-space"

        val url =
            PlexAuthUrlBuilder.buildOAuthUrl(
                clientId = clientId,
                pinCode = pinCode,
            )

        // Verify special characters are URL encoded
        assertTrue(
            "URL should encode & character",
            url.contains("client-with-special-%26%3D%3F"),
        )
        assertTrue(
            "URL should encode # and space characters",
            url.contains("pin-with-%23-and-space"),
        )
    }

    @Test
    fun buildOAuthUrl_consistentOutput() {
        // Same inputs should produce same output
        val clientId = "consistent-client"
        val pinCode = "consistent-pin"

        val url1 = PlexAuthUrlBuilder.buildOAuthUrl(clientId, pinCode)
        val url2 = PlexAuthUrlBuilder.buildOAuthUrl(clientId, pinCode)

        assertEquals(
            "Same inputs should produce identical URLs",
            url1,
            url2,
        )
    }

    @Test
    fun buildOAuthUrl_differentInputsProduceDifferentUrls() {
        val url1 =
            PlexAuthUrlBuilder.buildOAuthUrl(
                clientId = "client1",
                pinCode = "pin1",
            )
        val url2 =
            PlexAuthUrlBuilder.buildOAuthUrl(
                clientId = "client2",
                pinCode = "pin2",
            )

        assertTrue(
            "Different inputs should produce different URLs",
            url1 != url2,
        )
    }
}
