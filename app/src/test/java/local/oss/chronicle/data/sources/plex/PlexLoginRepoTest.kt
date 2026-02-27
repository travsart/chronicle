package local.oss.chronicle.data.sources.plex

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.sources.plex.model.OAuthResponse
import local.oss.chronicle.features.account.AccountManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Unit tests for [PlexLoginRepo].
 *
 * Tests OAuth PIN ID corruption bug fix:
 * - Transient network errors should NOT corrupt the PIN ID
 * - Only HTTP 404 errors (PIN expired) should clear the PIN ID
 */
class PlexLoginRepoTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var plexPrefsRepo: FakePlexPrefsRepo
    private lateinit var plexLoginService: PlexLoginService
    private lateinit var plexConfig: PlexConfig
    private lateinit var accountManager: AccountManager
    private lateinit var plexLoginRepo: PlexLoginRepo

    @Before
    fun setUp() {
        plexPrefsRepo = FakePlexPrefsRepo()
        plexLoginService = mockk(relaxed = true)
        plexConfig = mockk(relaxed = true)
        accountManager = mockk(relaxed = true)

        plexLoginRepo =
            PlexLoginRepo(
                plexPrefsRepo = plexPrefsRepo,
                plexLoginService = plexLoginService,
                plexConfig = plexConfig,
                accountManager = accountManager,
            )
    }

    @Test
    fun `checkForOAuthAccessToken does not corrupt PIN ID on network error`() =
        runTest {
            // Given: Valid PIN ID stored
            val originalPinId = 12345L
            plexPrefsRepo.oAuthTempId = originalPinId

            // When: Network error occurs (DNS resolution failure)
            coEvery { plexLoginService.getAuthPin(originalPinId) } throws
                UnknownHostException("Unable to resolve host plex.tv")

            // Call the method
            plexLoginRepo.checkForOAuthAccessToken()

            // Then: PIN ID should be preserved (NOT corrupted to -1)
            assertEquals(
                "PIN ID should not be corrupted on transient network error",
                originalPinId,
                plexPrefsRepo.oAuthTempId,
            )
        }

    @Test
    fun `checkForOAuthAccessToken does not corrupt PIN ID on timeout`() =
        runTest {
            // Given: Valid PIN ID stored
            val originalPinId = 12345L
            plexPrefsRepo.oAuthTempId = originalPinId

            // When: Timeout occurs
            coEvery { plexLoginService.getAuthPin(originalPinId) } throws
                SocketTimeoutException("timeout")

            // Call the method
            plexLoginRepo.checkForOAuthAccessToken()

            // Then: PIN ID should be preserved
            assertEquals(
                "PIN ID should not be corrupted on timeout",
                originalPinId,
                plexPrefsRepo.oAuthTempId,
            )
        }

    @Test
    fun `checkForOAuthAccessToken does not corrupt PIN ID on IOException`() =
        runTest {
            // Given: Valid PIN ID stored
            val originalPinId = 12345L
            plexPrefsRepo.oAuthTempId = originalPinId

            // When: IOException occurs
            coEvery { plexLoginService.getAuthPin(originalPinId) } throws
                IOException("Connection reset")

            // Call the method
            plexLoginRepo.checkForOAuthAccessToken()

            // Then: PIN ID should be preserved
            assertEquals(
                "PIN ID should not be corrupted on IOException",
                originalPinId,
                plexPrefsRepo.oAuthTempId,
            )
        }

    @Test
    fun `checkForOAuthAccessToken clears PIN ID on HTTP 404`() =
        runTest {
            // Given: Valid PIN ID stored
            val originalPinId = 12345L
            plexPrefsRepo.oAuthTempId = originalPinId

            // When: HTTP 404 error occurs (PIN expired/not found)
            val errorBody = "Not Found".toResponseBody("text/plain".toMediaType())
            val httpException = HttpException(Response.error<Any>(404, errorBody))
            coEvery { plexLoginService.getAuthPin(originalPinId) } throws httpException

            // Call the method
            plexLoginRepo.checkForOAuthAccessToken()

            // Then: PIN ID should be cleared (set to -1)
            assertEquals(
                "PIN ID should be cleared on HTTP 404 (PIN expired)",
                -1L,
                plexPrefsRepo.oAuthTempId,
            )
        }

    @Test
    fun `checkForOAuthAccessToken succeeds and stores token on valid response`() =
        runTest {
            // Given: Valid PIN ID stored
            val originalPinId = 12345L
            plexPrefsRepo.oAuthTempId = originalPinId

            // When: Successful response with auth token
            val validToken = "valid-auth-token-12345"
            val oauthResponse =
                OAuthResponse(
                    id = originalPinId,
                    clientIdentifier = "test-client-id",
                    code = "test-code",
                    authToken = validToken,
                )
            coEvery { plexLoginService.getAuthPin(originalPinId) } returns oauthResponse

            // Mock the subsequent getUsersForAccount call to avoid NPE
            coEvery { plexLoginService.getUsersForAccount() } throws IOException("Not testing user flow")

            // Call the method
            plexLoginRepo.checkForOAuthAccessToken()

            // Then: Auth token should be stored
            assertEquals(
                "Auth token should be stored on successful response",
                validToken,
                plexPrefsRepo.accountAuthToken,
            )
        }
}
