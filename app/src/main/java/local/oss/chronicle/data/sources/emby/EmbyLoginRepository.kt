package local.oss.chronicle.data.sources.emby

import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.features.account.AccountManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbyLoginRepository
    @Inject
    constructor(
        private val apiFactory: EmbyApiFactory,
        private val accountManager: AccountManager,
        private val sessionState: EmbySessionState,
        private val plexLoginRepo: IPlexLoginRepo,
    ) {
        suspend fun connect(
            serverUrl: String,
            username: String,
            password: String,
        ) {
            require(username.isNotBlank()) { "Enter an Emby username" }
            require(password.isNotEmpty()) { "Enter an Emby password" }

            val normalizedUrl = EmbyApiFactory.normalizeServerUrl(serverUrl)
            val service = apiFactory.create(normalizedUrl)
            val authorization =
                "MediaBrowser Client=\"Chronicle\", Device=\"Android\", " +
                    "DeviceId=\"${UUID.randomUUID()}\", Version=\"1.0\""
            val response =
                service.authenticate(
                    authorization,
                    EmbyAuthenticationRequest(username.trim(), password),
                )
            require(response.accessToken.isNotBlank()) { "Emby did not return an access token" }

            val systemInfo = service.getSystemInfo(response.accessToken)
            val views = service.getUserViews(response.user.id, response.accessToken).items
            require(views.isNotEmpty()) { "No libraries are available for this Emby user" }

            val accountId = "emby:account:${systemInfo.id}:${response.user.id}"
            val libraries =
                views.map { view ->
                    Library(
                        id = "emby:library:${view.id}",
                        accountId = accountId,
                        serverId = systemInfo.id,
                        serverName = systemInfo.serverName,
                        name = view.name,
                        type = view.collectionType.orEmpty(),
                        lastSyncedAt = null,
                        itemCount = 0,
                        isActive = false,
                        serverUrl = normalizedUrl,
                    )
                }

            val preferredLibrary =
                libraries.firstOrNull { it.type.equals("books", ignoreCase = true) }
                    ?: libraries.first()

            accountManager.addEmbyAccountWithLibraries(
                accountId = accountId,
                userName = response.user.name,
                avatarUrl = null,
                accessToken = response.accessToken,
                libraries = libraries,
                activeLibraryId = preferredLibrary.id,
            )
            sessionState.isEmbyActive = true
            plexLoginRepo.determineLoginState()
        }
    }
