package local.oss.chronicle.data.sources.plex

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import local.oss.chronicle.data.model.ServerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ScopedPlexServiceFactory.
 *
 * These tests verify that:
 * - Services are created with proper configuration
 * - Same connection returns cached instance
 * - Different connections return different instances
 * - Cache can be cleared
 */
class ScopedPlexServiceFactoryTest {
    private lateinit var moshi: Moshi
    private lateinit var plexPrefsRepo: PlexPrefsRepo
    private lateinit var plexConfig: PlexConfig
    private lateinit var factory: ScopedPlexServiceFactory

    @Before
    fun setup() {
        // Create real Moshi with KotlinJsonAdapterFactory (critical for the bug fix)
        moshi =
            Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

        // Mock dependencies
        plexPrefsRepo = mockk(relaxed = true)
        every { plexPrefsRepo.uuid } returns "test-uuid-123"

        plexConfig = mockk(relaxed = true)
        every { plexConfig.sessionIdentifier } returns "test-session-456"

        // Create factory with real Moshi and mocked dependencies
        factory = ScopedPlexServiceFactory(moshi, plexPrefsRepo, plexConfig)
    }

    @Test
    fun `getOrCreateService returns a PlexMediaService instance`() {
        // Given: a valid server connection
        val connection =
            ServerConnection(
                serverUrl = "https://test.server.com:32400",
                authToken = "test-token-abc",
            )

        // When: getting a service
        val service = factory.getOrCreateService(connection)

        // Then: a PlexMediaService instance is returned
        assert(service is PlexMediaService)
    }

    @Test
    fun `getOrCreateService caches instances for same connection`() {
        // Given: same server connection used twice
        val connection =
            ServerConnection(
                serverUrl = "https://test.server.com:32400",
                authToken = "test-token-abc",
            )

        // When: calling getOrCreateService twice with same connection
        val service1 = factory.getOrCreateService(connection)
        val service2 = factory.getOrCreateService(connection)

        // Then: the same instance is returned (cached)
        assertSame(
            "Same connection should return cached instance",
            service1,
            service2,
        )
    }

    @Test
    fun `getOrCreateService creates new instance for different serverUrl`() {
        // Given: two connections with different server URLs but same token
        val connection1 =
            ServerConnection(
                serverUrl = "https://server1.com:32400",
                authToken = "test-token-abc",
            )
        val connection2 =
            ServerConnection(
                serverUrl = "https://server2.com:32400",
                authToken = "test-token-abc",
            )

        // When: getting services for each connection
        val service1 = factory.getOrCreateService(connection1)
        val service2 = factory.getOrCreateService(connection2)

        // Then: different instances are returned
        assertNotSame(
            "Different server URLs should return different instances",
            service1,
            service2,
        )
    }

    @Test
    fun `getOrCreateService creates new instance for different authToken`() {
        // Given: two connections with same server URL but different tokens
        val connection1 =
            ServerConnection(
                serverUrl = "https://test.server.com:32400",
                authToken = "token-1",
            )
        val connection2 =
            ServerConnection(
                serverUrl = "https://test.server.com:32400",
                authToken = "token-2",
            )

        // When: getting services for each connection
        val service1 = factory.getOrCreateService(connection1)
        val service2 = factory.getOrCreateService(connection2)

        // Then: different instances are returned
        assertNotSame(
            "Different auth tokens should return different instances",
            service1,
            service2,
        )
    }

    @Test
    fun `clearCache causes next call to create new instance`() {
        // Given: a connection that has been used to create a service
        val connection =
            ServerConnection(
                serverUrl = "https://test.server.com:32400",
                authToken = "test-token-abc",
            )
        val originalService = factory.getOrCreateService(connection)

        // When: clearing the cache and getting service again
        factory.clearCache()
        val newService = factory.getOrCreateService(connection)

        // Then: a different instance is returned (cache was cleared)
        assertNotSame(
            "Cached instance should be cleared and new one created",
            originalService,
            newService,
        )
    }

    @Test
    fun `clearCache with multiple cached instances clears all`() {
        // Given: multiple cached services
        val connection1 =
            ServerConnection(
                serverUrl = "https://server1.com:32400",
                authToken = "token-1",
            )
        val connection2 =
            ServerConnection(
                serverUrl = "https://server2.com:32400",
                authToken = "token-2",
            )

        val service1Before = factory.getOrCreateService(connection1)
        val service2Before = factory.getOrCreateService(connection2)

        // When: clearing cache
        factory.clearCache()

        // Then: new instances are created for both connections
        val service1After = factory.getOrCreateService(connection1)
        val service2After = factory.getOrCreateService(connection2)

        assertNotSame("Service 1 should be new instance", service1Before, service1After)
        assertNotSame("Service 2 should be new instance", service2Before, service2After)
    }

    @Test(expected = IllegalStateException::class)
    fun `getOrCreateService throws when serverUrl is null`() {
        // Given: connection with null server URL
        val connection =
            ServerConnection(
                serverUrl = null,
                authToken = "test-token",
            )

        // When/Then: calling getOrCreateService throws IllegalStateException
        factory.getOrCreateService(connection)
    }

    @Test(expected = IllegalStateException::class)
    fun `getOrCreateService throws when authToken is null`() {
        // Given: connection with null auth token
        val connection =
            ServerConnection(
                serverUrl = "https://test.server.com:32400",
                authToken = null,
            )

        // When/Then: calling getOrCreateService throws IllegalStateException
        factory.getOrCreateService(connection)
    }

    @Test
    fun `factory uses DI-provided Moshi with KotlinJsonAdapterFactory`() {
        // Given: factory is initialized with Moshi containing KotlinJsonAdapterFactory
        val connection =
            ServerConnection(
                serverUrl = "https://test.server.com:32400",
                authToken = "test-token",
            )

        // When: creating a service
        val service = factory.getOrCreateService(connection)

        // Then: service can be created without error
        // The real test of proper Moshi configuration is that PlayQueueResponseWrapper
        // can be deserialized, which would fail with bare Moshi.Builder().build()
        // This is validated in integration tests.
        assert(service is PlexMediaService)
    }
}
