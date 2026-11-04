package local.oss.chronicle.data.sources.emby

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbySessionState
    @Inject
    constructor(private val preferences: SharedPreferences) {
        var isEmbyActive: Boolean
            get() = preferences.getString(ACTIVE_PROVIDER_KEY, PLEX_PROVIDER) == EMBY_PROVIDER
            set(value) {
                preferences.edit()
                    .putString(ACTIVE_PROVIDER_KEY, if (value) EMBY_PROVIDER else PLEX_PROVIDER)
                    .apply()
            }

        private companion object {
            const val ACTIVE_PROVIDER_KEY = "active_media_provider"
            const val PLEX_PROVIDER = "plex"
            const val EMBY_PROVIDER = "emby"
        }
    }