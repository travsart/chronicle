package local.oss.chronicle.data.sources.plex

import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.ServerConnection
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves server URL and authentication token for a given library ID.
 *
 * This component enables library-aware playback by maintaining a cache of server
 * connections per library. When a track from a specific library needs to be played,
 * this resolver provides the correct server URL and auth token to use.
 *
 * **Caching Strategy:**
 * - Uses ConcurrentHashMap for thread-safe O(1) lookups
 * - Cache persists for the lifetime of the application
 * - Cache can be invalidated on library/account changes
 *
 * **Fallback Behavior:**
 * - If library ID is null/empty, uses global PlexConfig
 * - If library has no serverUrl/authToken, uses global PlexConfig
 * - Ensures backward compatibility with single-account setups
 *
 * @see ServerConnection
 * @see LibraryRepository
 */
@Singleton
class ServerConnectionResolver
    @Inject
    constructor(
        private val libraryRepository: LibraryRepository,
        private val plexConfig: PlexConfig,
        private val plexPrefsRepo: PlexPrefsRepo,
    ) {
        /**
         * Thread-safe cache of server connections by library ID.
         * Key: libraryId (e.g., "plex:library:4")
         * Value: ServerConnection with URL and token
         */
        private val cache = ConcurrentHashMap<String, ServerConnection>()

        /**
         * Resolves server connection for a library ID.
         *
         * Resolution order:
         * 1. Check cache for existing connection
         * 2. If cache miss, query database for library's serverUrl and authToken
         * 3. If library has connection info, cache and return it
         * 4. If library lacks connection info, fall back to global PlexConfig
         *
         * @param libraryId The library ID (e.g., "plex:library:4"), or null to use global config
         * @return ServerConnection with URL and token (never null - always returns fallback if needed)
         */
        suspend fun resolve(libraryId: String): ServerConnection {
            // Check cache first
            cache[libraryId]?.let { cached ->
                // Only use cached value if it has both URL and token
                if (cached.serverUrl != null && cached.authToken != null) {
                    Timber.v("Cache hit for libraryId=$libraryId: serverUrl=${cached.serverUrl}")
                    return cached
                }
            }

            // Cache miss or incomplete data - query database
            val dbConnection = libraryRepository.getServerConnection(libraryId)

            // Build resolved connection (with fallback to global PlexConfig)
            val fallbackToken = plexPrefsRepo.server?.accessToken
                ?: plexPrefsRepo.user?.authToken
                ?: plexPrefsRepo.accountAuthToken

            val resolved = ServerConnection(
                serverUrl = dbConnection?.serverUrl ?: plexConfig.url,
                authToken = dbConnection?.authToken ?: fallbackToken,
            )

            // Cache the result
            cache[libraryId] = resolved

            if (dbConnection?.serverUrl != null && dbConnection.authToken != null) {
                Timber.d("Resolved connection for libraryId=$libraryId from database: serverUrl=${resolved.serverUrl}")
            } else {
                Timber.d("Using fallback PlexConfig for libraryId=$libraryId: serverUrl=${resolved.serverUrl}")
            }

            return resolved
        }

        /**
         * Clears all cached connections.
         * Call this when accounts change or when global configuration is updated.
         */
        fun clearCache() {
            cache.clear()
            Timber.d("Cleared all cached server connections")
        }

        /**
         * Invalidates the cached connection for a specific library.
         * Call this when a library is updated or removed.
         *
         * @param libraryId The library ID to invalidate
         */
        fun invalidate(libraryId: String) {
            cache.remove(libraryId)
            Timber.d("Invalidated cached connection for libraryId=$libraryId")
        }
    }
