package local.oss.chronicle.data.sources.emby

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Inject

interface EmbyService {
    @POST("Users/AuthenticateByName")
    suspend fun authenticate(
        @Header("Authorization") authorization: String,
        @Body request: EmbyAuthenticationRequest,
    ): EmbyAuthenticationResponse

    @GET("System/Info")
    suspend fun getSystemInfo(
        @Header("X-Emby-Token") accessToken: String,
    ): EmbySystemInfo

    @GET("Users/{userId}/Views")
    suspend fun getUserViews(
        @Path("userId") userId: String,
        @Header("X-Emby-Token") accessToken: String,
    ): EmbyQueryResult<EmbyView>

    @GET("Users/{userId}/Items")
    suspend fun getAudioItems(
        @Path("userId") userId: String,
        @Query("ParentId") parentId: String,
        @Query("Recursive") recursive: Boolean = true,
        @Query("MediaTypes") mediaTypes: String = "Audio",
        @Query("Fields") fields: String = "Genres,Overview,SortName,MediaSources",
        @Query("EnableUserData") enableUserData: Boolean = true,
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 200,
        @Header("X-Emby-Token") accessToken: String,
    ): EmbyQueryResult<EmbyItem>

    @POST("Sessions/Playing")
    suspend fun reportPlaybackStarted(
        @Header("X-Emby-Token") accessToken: String,
        @Body request: EmbyPlaybackRequest,
    )

    @POST("Sessions/Playing/Progress")
    suspend fun reportPlaybackProgress(
        @Header("X-Emby-Token") accessToken: String,
        @Body request: EmbyPlaybackRequest,
    )

    @POST("Users/{userId}/PlayingItems/{itemId}/Progress")
    suspend fun reportUserPlaybackProgress(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Query("MediaSourceId") mediaSourceId: String,
        @Query("PositionTicks") positionTicks: Long,
        @Query("IsPaused") isPaused: Boolean,
        @Query("PlaySessionId") playSessionId: String,
        @Header("X-Emby-Token") accessToken: String,
        @Body progress: EmbyUserPlaybackProgress,
    )

    @DELETE("Users/{userId}/PlayingItems/{itemId}")
    suspend fun reportUserPlaybackStopped(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Header("X-Emby-Token") accessToken: String,
    )

    @POST("Sessions/Playing/Stopped")
    suspend fun reportPlaybackStopped(
        @Header("X-Emby-Token") accessToken: String,
        @Body request: EmbyPlaybackRequest,
    )

    @POST("Users/{userId}/PlayedItems/{itemId}")
    suspend fun markPlayed(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Header("X-Emby-Token") accessToken: String,
    )

    @DELETE("Users/{userId}/PlayedItems/{itemId}")
    suspend fun markUnplayed(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Header("X-Emby-Token") accessToken: String,
    )
}

@JsonClass(generateAdapter = false)
data class EmbyAuthenticationRequest(
    @Json(name = "Username") val username: String,
    @Json(name = "Pw") val password: String,
)

@JsonClass(generateAdapter = false)
data class EmbyAuthenticationResponse(
    @Json(name = "AccessToken") val accessToken: String,
    @Json(name = "User") val user: EmbyUser,
)

@JsonClass(generateAdapter = false)
data class EmbyUser(
    @Json(name = "Id") val id: String,
    @Json(name = "Name") val name: String,
    @Json(name = "PrimaryImageTag") val primaryImageTag: String? = null,
)

@JsonClass(generateAdapter = false)
data class EmbySystemInfo(
    @Json(name = "Id") val id: String,
    @Json(name = "ServerName") val serverName: String,
)

@JsonClass(generateAdapter = false)
data class EmbyItem(
    @Json(name = "Id") val id: String,
    @Json(name = "Name") val name: String,
    @Json(name = "Type") val type: String? = null,
    @Json(name = "MediaType") val mediaType: String? = null,
    @Json(name = "ParentId") val parentId: String? = null,
    @Json(name = "Album") val album: String? = null,
    @Json(name = "AlbumId") val albumId: String? = null,
    @Json(name = "AlbumArtist") val albumArtist: String? = null,
    @Json(name = "Artists") val artists: List<String> = emptyList(),
    @Json(name = "Genres") val genres: List<String> = emptyList(),
    @Json(name = "SortName") val sortName: String? = null,
    @Json(name = "Overview") val overview: String? = null,
    @Json(name = "ProductionYear") val productionYear: Int? = null,
    @Json(name = "DateCreated") val dateCreated: String? = null,
    @Json(name = "RunTimeTicks") val runTimeTicks: Long? = null,
    @Json(name = "Size") val size: Long? = null,
    @Json(name = "IndexNumber") val indexNumber: Int? = null,
    @Json(name = "ParentIndexNumber") val parentIndexNumber: Int? = null,
    @Json(name = "PrimaryImageTag") val primaryImageTag: String? = null,
    @Json(name = "PrimaryImageItemId") val primaryImageItemId: String? = null,
    @Json(name = "AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @Json(name = "UserData") val userData: EmbyUserData? = null,
)

@JsonClass(generateAdapter = false)
data class EmbyUserData(
    @Json(name = "PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @Json(name = "LastPlayedDate") val lastPlayedDate: String? = null,
    @Json(name = "PlayCount") val playCount: Int? = null,
    @Json(name = "Played") val played: Boolean? = null,
)

@JsonClass(generateAdapter = false)
data class EmbyPlaybackRequest(
    @Json(name = "ItemId") val itemId: String,
    @Json(name = "PositionTicks") val positionTicks: Long,
    @Json(name = "PlaySessionId") val playSessionId: String,
    @Json(name = "MediaSourceId") val mediaSourceId: String = itemId,
    @Json(name = "PlayMethod") val playMethod: String = "DirectPlay",
    @Json(name = "QueueableMediaTypes") val queueableMediaTypes: List<String> = listOf("Audio"),
    @Json(name = "CanSeek") val canSeek: Boolean = true,
    @Json(name = "IsPaused") val isPaused: Boolean = false,
    @Json(name = "IsMuted") val isMuted: Boolean = false,
    @Json(name = "EventName") val eventName: String? = null,
)

@JsonClass(generateAdapter = false)
data class EmbyUserPlaybackProgress(
    @Json(name = "EventName") val eventName: String,
)

@JsonClass(generateAdapter = false)
data class EmbyQueryResult<T>(
    @Json(name = "Items") val items: List<T> = emptyList(),
    @Json(name = "TotalRecordCount") val totalRecordCount: Int = 0,
)

@JsonClass(generateAdapter = false)
data class EmbyView(
    @Json(name = "Id") val id: String,
    @Json(name = "Name") val name: String,
    @Json(name = "CollectionType") val collectionType: String? = null,
)

class EmbyApiFactory
    @Inject
    constructor(
        private val moshi: Moshi,
    ) {
        fun create(serverUrl: String): EmbyService {
            val baseUrl = normalizeServerUrl(serverUrl)
            return Retrofit
                .Builder()
                .baseUrl(baseUrl)
                .client(OkHttpClient.Builder().build())
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(EmbyService::class.java)
        }

        companion object {
            fun normalizeServerUrl(serverUrl: String): String {
                val parsed =
                    serverUrl.trim()
                        .toHttpUrlOrNull()
                        ?: throw IllegalArgumentException("Enter a valid server URL")
                require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
                    "Server URL must not contain credentials"
                }
                require(parsed.query == null && parsed.fragment == null) {
                    "Server URL must not contain a query or fragment"
                }

                val path = parsed.encodedPath.trimEnd('/')
                val servicePath = if (path.endsWith("/emby")) path else "$path/emby"
                return parsed
                    .newBuilder()
                    .encodedPath("$servicePath/")
                    .query(null)
                    .fragment(null)
                    .build()
                    .toString()
            }
        }
    }
