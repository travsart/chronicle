package local.oss.chronicle.data.sources.plex

import android.content.Context
import android.os.Build
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * **Threading:**
 * - The [currentLibraryId] setter is main-thread safe: it only invalidates the cached
 *   token and launches an asynchronous pre-warm; it never performs blocking I/O.
 * - [createDataSource] may block briefly on [ServerConnectionResolver.resolve] when the
 *   pre-warm has not completed yet. That is safe because ExoPlayer/Media3 only ever
 *   invoke it on background Loader threads, never on the main thread.
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
     * Scope used to pre-warm the library token cache off the main thread.
     * SupervisorJob ensures one failed pre-warm does not cancel future ones.
     */
    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Mutable library context - set by MediaPlayerService when loading a book for playback.
     * When non-null, the factory uses library-specific auth tokens for HTTP requests.
     *
     * MAIN-THREAD SAFE: this setter never blocks. Every set immediately invalidates the
     * cached token (so a stale token from another library is never used) and, for non-null
     * values, kicks off an asynchronous pre-warm on [prewarmScope]. The pre-warm must be
     * async because [ServerConnectionResolver.resolve] can perform Room DB queries,
     * keystore I/O and network probes - resolving it inline here previously caused
     * main-thread ANRs ("input dispatching timed out").
     *
     * Note: there is intentionally NO early-return for unchanged values - MediaPlayerService
     * re-pins the same library id after a forced re-resolve specifically to refresh the
     * cached token.
     */
    var currentLibraryId: String? = null
        set(value) {
            field = value
            // Invalidate any previously cached token immediately so a stale token from
            // another library is never used.
            cachedAuthToken = null
            if (value != null) {
                // Pre-warm asynchronously. This setter is called on the MAIN thread from
                // MediaSession callbacks; ServerConnectionResolver.resolve() can perform
                // DB, keystore and network I/O, so blocking here causes ANRs.
                prewarmScope.launch {
                    try {
                        val connection = serverConnectionResolver.resolve(value)
                        // Only publish if the library context is still the one we resolved for
                        if (currentLibraryId == value) {
                            cachedAuthToken = connection.authToken
                            Timber.d(
                                "[TokenInjection] Pre-resolved token for library $value: " +
                                    "${SecurityUtils.hashToken(connection.authToken)}",
                            )
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "[TokenInjection] Failed to pre-resolve token for library $value, will resolve on createDataSource()")
                    }
                }
            }
        }

    /**
     * Cached auth token for the current library.
     * Populated asynchronously when [currentLibraryId] is set, cleared on every set.
     * Written on [prewarmScope] (Dispatchers.IO), read on ExoPlayer Loader threads.
     */
    @Volatile
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
     * 1. Library-specific token cached by the [currentLibraryId] pre-warm
     * 2. Library-specific token resolved synchronously via [ServerConnectionResolver]
     * 3. Global token from PlexPrefsRepo (fallback for backward compatibility)
     *
     * Note: step 2 may block briefly when the pre-warm has not completed yet. That is
     * safe because ExoPlayer/Media3 only invoke this method on background Loader
     * threads, never on the main thread.
     */
    override fun createDataSource(): HttpDataSource {
        val factory = DefaultHttpDataSource.Factory()

        // Set user agent (static, safe to set once)
        factory.setUserAgent(Util.getUserAgent(context, APP_NAME))

        // Resolve auth token: library-specific (cached or resolved on demand) or global (fallback)
        val cachedToken = cachedAuthToken
        val libraryId = currentLibraryId
        var tokenSource = "global"
        val authToken: String =
            when {
                cachedToken != null -> {
                    tokenSource = "library-cached"
                    cachedToken
                }
                libraryId != null -> {
                    // The async pre-warm has not populated the cache yet; resolve synchronously.
                    // Safe here: createDataSource() only runs on ExoPlayer Loader threads.
                    val resolved =
                        try {
                            runBlocking { serverConnectionResolver.resolve(libraryId).authToken }
                        } catch (e: Exception) {
                            Timber.e(
                                e,
                                "[TokenInjection] Failed to resolve token for library $libraryId " +
                                    "in createDataSource(), falling back to global",
                            )
                            null
                        }
                    if (resolved != null) {
                        tokenSource = "library-resolved"
                        resolved
                    } else {
                        globalToken()
                    }
                }
                else -> globalToken()
            }

        if (BuildConfig.DEBUG) {
            val tokenHash = SecurityUtils.hashToken(authToken)
            Timber.d(
                "[TokenInjection] PlexHttpDataSourceFactory.createDataSource(): " +
                    "token=$tokenHash (source=$tokenSource), currentLibraryId=$currentLibraryId",
            )
        }

        // Build header map with FRESH token, merging with any additional properties
        val headers = buildHeaders(authToken)

        // Set headers on the factory
        factory.setDefaultRequestProperties(headers)

        return factory.createDataSource()
    }

    /**
     * Reads the global token chain from [plexPrefsRepo] (fallback when no library context
     * or library resolution failed). Non-null because accountAuthToken is non-null.
     */
    private fun globalToken(): String {
        // Fallback to global token from preferences
        val serverToken = plexPrefsRepo.server?.accessToken
        val userToken = plexPrefsRepo.user?.authToken
        val accountToken = plexPrefsRepo.accountAuthToken

        // Select most privileged token available (matches PlexInterceptor logic)
        return serverToken ?: userToken ?: accountToken
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
