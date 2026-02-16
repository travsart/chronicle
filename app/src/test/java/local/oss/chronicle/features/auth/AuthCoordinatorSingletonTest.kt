package local.oss.chronicle.features.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AuthCoordinatorSingleton].
 *
 * Tests the singleton's coordinator registration/unregistration and
 * browser return callback delegation.
 */
class AuthCoordinatorSingletonTest {
    private lateinit var mockCoordinator: PlexAuthCoordinator

    @Before
    fun setUp() {
        // Create mock coordinator
        mockCoordinator = mockk(relaxed = true)

        // Ensure singleton starts clean
        AuthCoordinatorSingleton.unregister()
    }

    @After
    fun tearDown() {
        // Clean up singleton state after each test
        AuthCoordinatorSingleton.unregister()
    }

    @Test
    fun register_storesCoordinator() {
        // When: Register a coordinator
        AuthCoordinatorSingleton.register(mockCoordinator)

        // Then: Calling onBrowserReturned should delegate to the coordinator
        AuthCoordinatorSingleton.onBrowserReturned()

        verify(exactly = 1) { mockCoordinator.onBrowserReturned() }
    }

    @Test
    fun unregister_clearsCoordinator() {
        // Given: A registered coordinator
        AuthCoordinatorSingleton.register(mockCoordinator)

        // When: Unregister the coordinator
        AuthCoordinatorSingleton.unregister()

        // Then: Calling onBrowserReturned should NOT call the coordinator (no crash)
        AuthCoordinatorSingleton.onBrowserReturned()

        verify(exactly = 0) { mockCoordinator.onBrowserReturned() }
    }

    @Test
    fun onBrowserReturned_withNoCoordinator_doesNotCrash() {
        // Given: No coordinator registered
        AuthCoordinatorSingleton.unregister()

        // When/Then: Calling onBrowserReturned should not crash
        AuthCoordinatorSingleton.onBrowserReturned() // Should log warning but not crash
    }

    @Test
    fun register_replacesExistingCoordinator() {
        // Given: First coordinator registered
        val firstCoordinator = mockk<PlexAuthCoordinator>(relaxed = true)
        AuthCoordinatorSingleton.register(firstCoordinator)

        // When: Second coordinator registered (replaces first)
        val secondCoordinator = mockk<PlexAuthCoordinator>(relaxed = true)
        AuthCoordinatorSingleton.register(secondCoordinator)

        // Then: Only second coordinator receives callbacks
        AuthCoordinatorSingleton.onBrowserReturned()

        verify(exactly = 0) { firstCoordinator.onBrowserReturned() }
        verify(exactly = 1) { secondCoordinator.onBrowserReturned() }
    }

    @Test
    fun onBrowserReturned_delegatesToRegisteredCoordinator() {
        // Given: Coordinator registered
        every { mockCoordinator.onBrowserReturned() } returns Unit
        AuthCoordinatorSingleton.register(mockCoordinator)

        // When: Browser returns
        AuthCoordinatorSingleton.onBrowserReturned()

        // Then: Coordinator's onBrowserReturned is called
        verify(exactly = 1) { mockCoordinator.onBrowserReturned() }
    }

    @Test
    fun multipleOnBrowserReturned_callsCoordinatorMultipleTimes() {
        // Given: Coordinator registered
        AuthCoordinatorSingleton.register(mockCoordinator)

        // When: Browser returns multiple times
        AuthCoordinatorSingleton.onBrowserReturned()
        AuthCoordinatorSingleton.onBrowserReturned()
        AuthCoordinatorSingleton.onBrowserReturned()

        // Then: Coordinator receives all callbacks
        verify(exactly = 3) { mockCoordinator.onBrowserReturned() }
    }

    @Test
    fun registerAfterUnregister_worksCorrectly() {
        // Given: Register, unregister, then re-register
        val firstCoordinator = mockk<PlexAuthCoordinator>(relaxed = true)
        AuthCoordinatorSingleton.register(firstCoordinator)
        AuthCoordinatorSingleton.unregister()

        val secondCoordinator = mockk<PlexAuthCoordinator>(relaxed = true)
        AuthCoordinatorSingleton.register(secondCoordinator)

        // When: Browser returns
        AuthCoordinatorSingleton.onBrowserReturned()

        // Then: Only second coordinator receives callback
        verify(exactly = 0) { firstCoordinator.onBrowserReturned() }
        verify(exactly = 1) { secondCoordinator.onBrowserReturned() }
    }
}
