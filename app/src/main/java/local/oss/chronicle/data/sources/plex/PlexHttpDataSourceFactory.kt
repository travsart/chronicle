package local.oss.chronicle.data.sources.plex

import android.content.Context
import android.os.Build
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.util.SecurityUtils
import timber.log.Timber

/**
 * Custom HttpDataSource.Factory that reads Plex authentication tokens LAZILY
 * on every createDataSource() call, preventing stale token issues.
 *
 * This solves the race condition where MediaPlayerService's DI graph is constructed
 * before PlexPrefsRepo has loaded tokens from SharedPreferences. By reading tokens
 * fresh on each data source creation, we always use the current auth state.
 *
 * @param context Application context for user agent generation
 * @param plexPrefsRepo Repository providing fresh token values
 *
 * @see PlexInterceptor for the equivalent pattern used in Retrofit networking
 */
class PlexHttpDataSourceFactory(
    private val context: Context,
    private val plexPrefsRepo: PlexPrefsRepo,
) : HttpDataSource.Factory {

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
     * Creates a new HttpDataSource with FRESH token values read from PlexPrefsRepo.
     * Called by ExoPlayer for each media segment fetch.
     */
    override fun createDataSource(): HttpDataSource {
        val factory = DefaultHttpDataSource.Factory()

        // Set user agent (static, safe to set once)
        factory.setUserAgent(Util.getUserAgent(context, APP_NAME))

        // Read tokens FRESH from preferences on each call
        val serverToken = plexPrefsRepo.server?.accessToken
        val userToken = plexPrefsRepo.user?.authToken
        val accountToken = plexPrefsRepo.accountAuthToken

        // Select most privileged token available (matches PlexInterceptor logic)
        val authToken = serverToken ?: userToken ?: accountToken

        if (BuildConfig.DEBUG) {
            val tokenHash = SecurityUtils.hashToken(authToken)
            Timber.d(
                "[TokenInjection] PlexHttpDataSourceFactory.createDataSource(): " +
                    "token=$tokenHash, hasServerToken=${serverToken != null}, " +
                    "hasUserToken=${userToken != null}, hasAccountToken=${accountToken.isNotEmpty()}"
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
        val headers = mutableMapOf(
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
