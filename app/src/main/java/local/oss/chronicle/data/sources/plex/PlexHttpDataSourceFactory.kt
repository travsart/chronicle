package local.oss.chronicle.data.sources.plex

import android.content.Context
import android.os.Build
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.runBlocking
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.application.Injector
import local.oss.chronicle.util.SecurityUtils
import timber.log.Timber

/**
 * Custom HttpDataSource.Factory that reads Plex authentication tokens LAZILY
 * on every createDataSource() call, preventing stale token issues.
 *
 * **Phase 3 Enhancement (Multi-Library Support):**
 * Now supports library-aware token injection. When [currentLibraryId] is set,
 * the factory uses [ServerConnectionResolver] to get the library-specific auth token
 * instead of the global token from [plexPrefsRepo].
 *
 * This solves the race condition where MediaPlayerService's DI graph is constructed
 * before PlexPrefsRepo has loaded tokens from SharedPreferences. By reading tokens
 * fresh on each data source creation, we always use the current auth state.
 *
 * @param context Application context for user agent generation
 * @param plexPrefsRepo Repository providing fresh token values (fallback when no library context)
 *
 * @see PlexInterceptor for the equivalent pattern used in Retrofit networking
 * @see ServerConnectionResolver for library-aware server/token resolution
 */
class PlexHttpDataSourceFactory(
    private val context: Context,
    private val plexPrefsRepo: PlexPrefsRepo,
) : HttpDataSource.Factory {
    /**
     * ServerConnectionResolver for library-aware token resolution.
     * Injected lazily to avoid circular dependencies during DI initialization.
     */
    private val serverConnectionResolver: ServerConnectionResolver by lazy {
        Injector.get().serverConnectionResolver()
    }

    /**
     * Mutable library context - set by MediaPlayerService when loading a book for playback.
     * When non-null, the factory uses library-specific auth tokens for HTTP requests.
     */
    var currentLibraryId: String? = null
        set(value) {
            field = value
            // Pre-resolve and cache the token for this library to avoid blocking on createDataSource()
            // runBlocking is acceptable here because ServerConnectionResolver has an in-memory cache
            cachedAuthToken =
                value?.let { libId ->
                    runBlocking {
                        try {
                            val connection = serverConnectionResolver.resolve(libId)
                            Timber.d(
                                "[TokenInjection] Pre-resolved token for library $libId: " +
                                    "${SecurityUtils.hashToken(connection.authToken)}",
                            )
                            connection.authToken
                        } catch (e: Exception) {
                            Timber.e(e, "[TokenInjection] Failed to resolve token for library $libId, falling back to global")
                            null
                        }
                    }
                }
        }

    /**
     * Cached auth token for the current library.
     * Populated when [currentLibraryId] is set, cleared when set to null.
     */
    private var cachedAuthToken: String? = null

    companion object {
        /**
         * Client profile declares what audio formats this app can directly play.
         * Must match the profile in PlexInterceptor for consistency.
         */
        private const val CLIENT_PROFILE_EXTRA =
            "add-direct-play-profile(type=musicProfile&container=mp4,m4a,m4b,mp3,flac,ogg,opus&audioCodec=aac,mp3,flac,vorbis,opus&videoCodec=*&subtitleCodec=*)"
    }

    /**
     * Additional request properties that can be set by callers.
     * Note: These do NOT include the auth token, which is read fresh on each createDataSource() call.
     */
    private val additionalRequestProperties = mutableMapOf<String, String>()

    /**
     * Sets default request properties. This implementation stores them but does NOT
     * include auth tokens here - tokens are read fresh on each createDataSource() call.
     */
    override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
        additionalRequestProperties.clear()
        additionalRequestProperties.putAll(defaultRequestProperties)
        return this
    }

    /**
     * Creates a new HttpDataSource with FRESH token values.
     * Called by ExoPlayer for each media segment fetch.
     *
     * Token resolution priority:
     * 1. Library-specific token (if [currentLibraryId] is set and token was cached)
     * 2. Global token from PlexPrefsRepo (fallback for backward compatibility)
     */
    override fun createDataSource(): HttpDataSource {
        val factory = DefaultHttpDataSource.Factory()

        // Set user agent (static, safe to set once)
        factory.setUserAgent(Util.getUserAgent(context, APP_NAME))

        // Resolve auth token: library-specific (if available) or global (fallback)
        val authToken =
            cachedAuthToken ?: run {
                // Fallback to global token from preferences
                val serverToken = plexPrefsRepo.server?.accessToken
                val userToken = plexPrefsRepo.user?.authToken
                val accountToken = plexPrefsRepo.accountAuthToken

                // Select most privileged token available (matches PlexInterceptor logic)
                serverToken ?: userToken ?: accountToken
            }

        if (BuildConfig.DEBUG) {
            val tokenHash = SecurityUtils.hashToken(authToken)
            val source = if (cachedAuthToken != null) "library-specific" else "global"
            Timber.d(
                "[TokenInjection] PlexHttpDataSourceFactory.createDataSource(): " +
                    "token=$tokenHash (source=$source), currentLibraryId=$currentLibraryId",
            )
        }

        // Build header map with FRESH token, merging with any additional properties
        val headers = buildHeaders(authToken)

        // Set headers on the factory
        factory.setDefaultRequestProperties(headers)

        return factory.createDataSource()
    }

    /**
     * Build Plex-required HTTP headers with the current auth token.
     * Must include all headers required by Plex Media Server API.
     */
    private fun buildHeaders(authToken: String): Map<String, String> {
        val headers =
            mutableMapOf(
                "X-Plex-Platform" to "Android",
                "X-Plex-Provides" to "player",
                "X-Plex-Client-Name" to APP_NAME,
                "X-Plex-Client-Identifier" to plexPrefsRepo.uuid,
                "X-Plex-Version" to BuildConfig.VERSION_NAME,
                "X-Plex-Product" to APP_NAME,
                "X-Plex-Platform-Version" to Build.VERSION.RELEASE,
                "X-Plex-Device" to Build.MODEL,
                "X-Plex-Device-Name" to Build.MODEL,
                "X-Plex-Session-Identifier" to plexPrefsRepo.uuid,
                "X-Plex-Client-Profile-Extra" to CLIENT_PROFILE_EXTRA,
            )

        // Only add auth token if non-empty
        if (authToken.isNotEmpty()) {
            headers["X-Plex-Token"] = authToken
        }

        // Merge any additional request properties that were set externally
        headers.putAll(additionalRequestProperties)

        return headers
    }
}
