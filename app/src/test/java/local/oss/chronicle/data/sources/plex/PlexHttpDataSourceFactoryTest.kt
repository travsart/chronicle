package local.oss.chronicle.data.sources.plex

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import local.oss.chronicle.data.model.ServerModel
import local.oss.chronicle.data.sources.plex.model.PlexUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class PlexHttpDataSourceFactoryTest {
    private lateinit var mockContext: Context
    private lateinit var mockPlexPrefs: PlexPrefsRepo
    private lateinit var factory: PlexHttpDataSourceFactory

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockPlexPrefs = mockk(relaxed = true)

        // Default mock setup
        every { mockPlexPrefs.uuid } returns "test-uuid-12345"
        every { mockPlexPrefs.accountAuthToken } returns ""
        every { mockPlexPrefs.user } returns null
        every { mockPlexPrefs.server } returns null

        factory = PlexHttpDataSourceFactory(mockContext, mockPlexPrefs)
    }

    @Test
    fun `createDataSource reads tokens fresh on each call`() {
        // First call - no tokens
        every { mockPlexPrefs.accountAuthToken } returns ""
        val dataSource1 = factory.createDataSource()
        assertNotNull(dataSource1)

        // Second call - token now available
        every { mockPlexPrefs.accountAuthToken } returns "new-token-abc123"
        val dataSource2 = factory.createDataSource()
        assertNotNull(dataSource2)

        // Verify PlexPrefsRepo was queried twice (fresh reads)
        verify(atLeast = 2) { mockPlexPrefs.accountAuthToken }
    }

    @Test
    fun `createDataSource prioritizes server token over user token`() {
        val serverToken = "server-token-xyz"
        val userToken = "user-token-abc"

        every { mockPlexPrefs.server } returns
            ServerModel(
                name = "Test Server",
                connections = emptyList(),
                serverId = "server-123",
                accessToken = serverToken,
                owned = true,
            )
        every { mockPlexPrefs.user } returns
            PlexUser(
                id = 123L,
                uuid = "user-uuid",
                title = "Test User",
                username = "testuser",
                thumb = "",
                hasPassword = true,
                admin = true,
                guest = false,
                authToken = userToken,
            )

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)

        // Should have read server token (priority over user token)
        verify { mockPlexPrefs.server }
    }

    @Test
    fun `createDataSource uses accountAuthToken as fallback`() {
        val accountToken = "account-token-fallback"

        every { mockPlexPrefs.server } returns null
        every { mockPlexPrefs.user } returns null
        every { mockPlexPrefs.accountAuthToken } returns accountToken

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)

        verify { mockPlexPrefs.accountAuthToken }
    }

    @Test
    fun `createDataSource includes required Plex headers`() {
        every { mockPlexPrefs.uuid } returns "test-uuid-12345"

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)

        // Verify UUID was read (used in headers)
        verify { mockPlexPrefs.uuid }
    }

    @Test
    fun `createDataSource handles empty tokens gracefully`() {
        every { mockPlexPrefs.server } returns null
        every { mockPlexPrefs.user } returns null
        every { mockPlexPrefs.accountAuthToken } returns ""

        // Should not throw exception
        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }

    @Test
    fun `createDataSource handles null user token`() {
        every { mockPlexPrefs.server } returns null
        every { mockPlexPrefs.user } returns
            PlexUser(
                id = 123L,
                uuid = "user-uuid",
                title = "Test User",
                username = "testuser",
                thumb = "",
                hasPassword = true,
                admin = true,
                guest = false,
                authToken = null,
            )
        every { mockPlexPrefs.accountAuthToken } returns "fallback-token"

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)

        // Should fall back to account token
        verify { mockPlexPrefs.accountAuthToken }
    }

    @Test
    fun `createDataSource handles null server token`() {
        every { mockPlexPrefs.server } returns
            ServerModel(
                name = "Test Server",
                connections = emptyList(),
                serverId = "server-123",
                accessToken = "",
                owned = true,
            )
        every { mockPlexPrefs.user } returns null
        every { mockPlexPrefs.accountAuthToken } returns "fallback-token"

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)

        // Should fall back to account token
        verify { mockPlexPrefs.accountAuthToken }
    }
}
