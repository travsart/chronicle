package local.oss.chronicle.data.sources.plex

import local.oss.chronicle.data.local.AccountRepository
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.features.account.CredentialManager
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
 * - Primary: Uses library.authToken from database
 * - Fallback 1: If library.authToken is null/empty, looks up Account credentials
 * - Fallback 2: If Account lookup fails, uses global PlexPrefsRepo
 * - **Self-healing:** Updates library.authToken in DB when resolved from fallback
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
        private val accountRepository: AccountRepository,
        private val credentialManager: CredentialManager,
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
            val library = libraryRepository.getLibraryById(libraryId)
            val dbConnection = libraryRepository.getServerConnection(libraryId)

            // Detect corrupted authToken (raw JSON string from previous bug)
            val libraryToken = dbConnection?.authToken
            val isTokenCorrupted = libraryToken?.startsWith("{") == true
            if (isTokenCorrupted) {
                Timber.w("Library authToken appears corrupted (JSON string) for $libraryId, treating as empty")
            }
            val validLibraryToken = if (isTokenCorrupted) null else libraryToken

            // Attempt to resolve authToken with multi-tier fallback
            var resolvedToken = validLibraryToken
            var usedFallback = false
            var selfHealNeeded = isTokenCorrupted // Need to heal if token was corrupted

            if (resolvedToken.isNullOrEmpty()) {
                Timber.w("Library $libraryId has empty authToken, attempting fallback")

                if (library != null) {
                    // Fallback 1: Try to get token from library's parent Account
                    val credentialsJson =
                        try {
                            credentialManager.getCredentials(library.accountId)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to retrieve credentials for account ${library.accountId}")
                            null
                        }

                    if (!credentialsJson.isNullOrEmpty()) {
                        // Parse JSON credentials to extract userToken
                        // Using simple string parsing to avoid org.json dependency issues in unit tests
                        val userToken = extractUserToken(credentialsJson)

                        if (userToken.isNotEmpty()) {
                            resolvedToken = userToken
                            usedFallback = true
                            selfHealNeeded = true
                            Timber.i("Resolved authToken from Account credentials for libraryId=$libraryId")
                        }
                    }
                }

                // Fallback 2: Use global PlexPrefsRepo (last resort) if still no token
                if (resolvedToken.isNullOrEmpty()) {
                    resolvedToken = plexPrefsRepo.server?.accessToken
                        ?: plexPrefsRepo.user?.authToken
                        ?: plexPrefsRepo.accountAuthToken

                    if (!resolvedToken.isNullOrEmpty()) {
                        usedFallback = true
                        if (library != null) {
                            selfHealNeeded = true
                        }
                        Timber.w("Resolved authToken from global PlexPrefsRepo for libraryId=$libraryId (last resort)")
                    }
                }
            }

            // Don't fall back to PlexConfig.url if it's still the placeholder
            val fallbackUrl = plexConfig.url.takeUnless { it == PlexConfig.PLACEHOLDER_URL }

            val resolved =
                ServerConnection(
                    serverUrl = dbConnection?.serverUrl ?: fallbackUrl,
                    authToken = resolvedToken,
                )

            // Cache the result
            cache[libraryId] = resolved

            // Self-healing: Update library in DB with resolved token for future use
            if (selfHealNeeded && library != null && !resolvedToken.isNullOrEmpty()) {
                try {
                    val updatedLibrary = library.copy(authToken = resolvedToken)
                    libraryRepository.updateLibrary(updatedLibrary)
                    Timber.i("Self-heal: Updated library $libraryId with resolved authToken")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to self-heal library $libraryId authToken")
                }
            }

            if (dbConnection?.serverUrl != null && dbConnection.authToken != null && !usedFallback) {
                Timber.d("Resolved connection for libraryId=$libraryId from database: serverUrl=${resolved.serverUrl}")
            } else if (usedFallback) {
                Timber.w(
                    "Used fallback for libraryId=$libraryId: serverUrl=${resolved.serverUrl}, tokenSource=${if (selfHealNeeded) "Account/PlexPrefs" else "unknown"}",
                )
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

        /**
         * Extracts userToken from credentials JSON string.
         * Format: {"userToken":"abc123","serverToken":"xyz789"}
         *
         * Using simple string parsing to avoid org.json.JSONObject dependency
         * issues in unit tests (android.jar stubs throw exceptions).
         */
        private fun extractUserToken(credentialsJson: String): String {
            return try {
                val userTokenPattern = """"userToken"\s*:\s*"([^"]+)"""".toRegex()
                userTokenPattern.find(credentialsJson)?.groupValues?.get(1) ?: ""
            } catch (e: Exception) {
                Timber.e(e, "Failed to extract userToken from credentials JSON")
                ""
            }
        }
    }
