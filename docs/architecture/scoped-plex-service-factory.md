# Scoped PlexMediaService Factory Design

**Status**: Design Approved  
**Date**: 2026-02-26  
**Related Issues**: Moshi deserialization bug in `startMediaSession()` causing 404 timeline errors

## Problem Statement

Both [`PlaybackUrlResolver`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlaybackUrlResolver.kt) and [`PlexProgressReporter`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexProgressReporter.kt) independently create "scoped" Retrofit instances (per-library, with library-specific base URL and auth token). Both contain a `createScopedService()` method that duplicates the same configuration but does it **incorrectly**.

### The Bug

Both classes use:
```kotlin
.addConverterFactory(MoshiConverterFactory.create())  // Bare Moshi!
```

Instead of:
```kotlin
.addConverterFactory(MoshiConverterFactory.create(moshi))  // Properly configured Moshi from DI
```

This causes:
1. `startMediaSession()` (POST `/playQueues`) fails because [`PlayQueueResponseWrapper`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/model/PlayQueueResponse.kt) can't be deserialized (Kotlin data classes require `KotlinJsonAdapterFactory`)
2. No play queue is registered with Plex server → `playQueueItemCache` stays empty
3. `GET /:/timeline` progress updates return HTTP 404 because Plex has no active session
4. Playback still works because ExoPlayer uses direct file URLs via [`PlexHttpDataSourceFactory`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexHttpDataSourceFactory.kt) (no Moshi needed)

### Additional Issues

The two implementations also differ in header completeness:

- **PlaybackUrlResolver**: Missing `X-Plex-Session-Identifier` and `X-Plex-Client-Name`, uses wrong `X-Plex-Client-Identifier` (uses `plexConfig.sessionIdentifier` instead of `plexPrefsRepo.uuid`)
- **PlexProgressReporter**: Has correct headers matching [`PlexInterceptor`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt)

## Proposed Solution

Create a centralized `ScopedPlexServiceFactory` class that:

1. **Centralizes scoped PlexMediaService creation** — single source of truth for per-library Retrofit instances
2. **Takes properly configured Moshi from DI** — uses the `Moshi` instance with `KotlinJsonAdapterFactory` from [`AppModule`](../../app/src/main/java/local/oss/chronicle/injection/modules/AppModule.kt)
3. **Creates scoped OkHttpClient instances** — each with per-request auth token interceptor
4. **Caches scoped services** — per `(serverUrl, authToken)` to avoid recreating Retrofit instances
5. **Injectable via Dagger** — `@Singleton` scoped in `AppComponent`

## Detailed Design

### Class Signature

```kotlin
package local.oss.chronicle.data.sources.plex

import com.squareup.moshi.Moshi
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.data.model.ServerConnection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating library-scoped PlexMediaService instances.
 *
 * Creates Retrofit instances with library-specific base URLs and auth tokens,
 * using properly configured Moshi from dependency injection to avoid deserialization bugs.
 *
 * Caches instances per (serverUrl, authToken) to avoid recreating Retrofit/OkHttp on every request.
 *
 * This centralizes the scoped service creation pattern previously duplicated in
 * PlaybackUrlResolver and PlexProgressReporter, fixing the bare Moshi bug that
 * prevented startMediaSession() from working.
 *
 * @see PlaybackUrlResolver
 * @see PlexProgressReporter
 * @see ServerConnectionResolver
 */
@Singleton
class ScopedPlexServiceFactory @Inject constructor(
    private val moshi: Moshi,
    private val plexPrefsRepo: PlexPrefsRepo,
    private val plexConfig: PlexConfig,
) {
    // Implementation below
}
```

### Cache Key Design

```kotlin
/**
 * Cache key combining server URL and auth token.
 * Two connections are considered identical if they have the same URL and token.
 */
private data class ServiceCacheKey(
    val serverUrl: String,
    val authToken: String,
)
```

**Rationale**: Using both `serverUrl` and `authToken` ensures:
- Different servers get different service instances
- Same server with different auth tokens (e.g., different accounts) get different instances
- Same server + token combination reuses the cached instance

### Service Cache

```kotlin
/**
 * Thread-safe cache of scoped PlexMediaService instances.
 * ConcurrentHashMap handles concurrent reads/writes from multiple threads.
 */
private val serviceCache = ConcurrentHashMap<ServiceCacheKey, PlexMediaService>()
```

**Thread Safety**: `ConcurrentHashMap` allows concurrent reads and uses fine-grained locking for writes, making it safe for:
- Worker threads (PlexSyncScrobbleWorker)
- Service threads (MediaPlayerService)
- UI thread
- Coroutine dispatchers

### Public API

#### `getOrCreateService()`

```kotlin
/**
 * Gets or creates a scoped PlexMediaService for the given server connection.
 *
 * Caches instances to avoid recreating Retrofit/OkHttp for the same connection.
 * Thread-safe for concurrent access from workers, services, and UI.
 *
 * @param connection Server connection with URL and auth token
 * @return PlexMediaService configured for this library
 * @throws IllegalStateException if serverUrl or authToken is null
 */
fun getOrCreateService(connection: ServerConnection): PlexMediaService {
    val serverUrl = connection.serverUrl 
        ?: throw IllegalStateException("No server URL in connection")
    val authToken = connection.authToken 
        ?: throw IllegalStateException("No auth token in connection")

    val cacheKey = ServiceCacheKey(serverUrl, authToken)
    
    // ConcurrentHashMap.getOrPut is thread-safe
    return serviceCache.getOrPut(cacheKey) {
        createService(serverUrl, authToken)
    }
}
```

**Usage Pattern**:
```kotlin
// In PlaybackUrlResolver
private suspend fun resolveUrlInternal(track: MediaItemTrack, libraryId: String): String {
    val connection = serverConnectionResolver.resolve(libraryId)
    val service = scopedPlexServiceFactory.getOrCreateService(connection)
    val decision = service.getPlaybackDecision(path = metadataPath, ...)
    // ...
}
```

#### `clearCache()`

```kotlin
/**
 * Clears the service cache.
 * Call when:
 * - User logs out
 * - Auth tokens are invalidated
 * - Server connections change
 */
fun clearCache() {
    serviceCache.clear()
}
```

### Private Implementation

#### `createService()`

```kotlin
/**
 * Creates a new PlexMediaService instance for the given server and token.
 * Uses properly configured Moshi from DI to avoid deserialization bugs.
 *
 * CRITICAL: Uses moshi from DI (with KotlinJsonAdapterFactory) instead of
 * bare MoshiConverterFactory.create() which cannot deserialize Kotlin data classes.
 *
 * @param serverUrl Base URL for the Plex server
 * @param authToken Authentication token for this server
 * @return PlexMediaService configured for this server
 */
private fun createService(serverUrl: String, authToken: String): PlexMediaService {
    // Create OkHttp client with scoped auth interceptor
    val client = OkHttpClient.Builder()
        .addInterceptor(createScopedInterceptor(authToken))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Create Retrofit with library-specific base URL and DI-provided Moshi
    val retrofit = Retrofit.Builder()
        .baseUrl(serverUrl)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))  // FIX: Use DI Moshi!
        .build()

    return retrofit.create(PlexMediaService::class.java)
}
```

**Why not reuse `mediaOkHttpClient` from DI?**

The existing `@Named(OKHTTP_CLIENT_MEDIA) mediaOkHttpClient` from [`AppModule`](../../app/src/main/java/local/oss/chronicle/injection/modules/AppModule.kt:156-168) has:
```kotlin
.addInterceptor(plexConfig.plexMediaInterceptor)
```

[`PlexInterceptor`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt) uses `plexConfig.url` which is the **global active server URL** that changes when switching contexts. For scoped services, we need:

1. **Fixed base URL per instance** (from Retrofit's `baseUrl()`)
2. **Fixed auth token per instance** (from scoped interceptor)

Therefore, we create a **new** OkHttpClient with only the scoped interceptor, but reuse the same timeout configurations.

#### `createScopedInterceptor()`

```kotlin
/**
 * Creates an OkHttp interceptor with scoped auth token.
 * Headers match PlexInterceptor exactly for proper Plex correlation.
 *
 * CRITICAL: Uses plexPrefsRepo.uuid (NOT plexConfig.sessionIdentifier) for
 * X-Plex-Client-Identifier to ensure consistency with main interceptor and
 * proper play queue → timeline correlation on Plex dashboard.
 *
 * @param authToken The auth token for this specific request
 * @return Interceptor that adds Plex headers to requests
 */
private fun createScopedInterceptor(authToken: String): Interceptor {
    return Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("X-Plex-Platform", "Android")
            .header("X-Plex-Provides", "player")
            .header("X-Plex-Client-Identifier", plexPrefsRepo.uuid)
            .header("X-Plex-Version", BuildConfig.VERSION_NAME)
            .header("X-Plex-Product", APP_NAME)
            .header("X-Plex-Platform-Version", Build.VERSION.RELEASE)
            .header("X-Plex-Session-Identifier", plexConfig.sessionIdentifier)
            .header("X-Plex-Client-Name", APP_NAME)
            .header("X-Plex-Device", Build.MODEL)
            .header("X-Plex-Device-Name", Build.MODEL)
            .header("X-Plex-Client-Profile-Extra", PlexInterceptor.CLIENT_PROFILE_EXTRA)
            .header("X-Plex-Token", authToken)
            .build()

        chain.proceed(request)
    }
}
```

**Header Alignment**:

This matches [`PlexInterceptor`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt:49-80) exactly:

| Header | Value | Notes |
|--------|-------|-------|
| `X-Plex-Client-Identifier` | `plexPrefsRepo.uuid` | **CRITICAL**: Must match main interceptor for dashboard correlation |
| `X-Plex-Session-Identifier` | `plexConfig.sessionIdentifier` | Unique per app launch |
| `X-Plex-Client-Profile-Extra` | `PlexInterceptor.CLIENT_PROFILE_EXTRA` | Reuses constant from PlexInterceptor |
| `X-Plex-Token` | `authToken` (parameter) | Per-library token |

**Why `plexPrefsRepo.uuid` not `plexConfig.sessionIdentifier`?**

From [`PlexInterceptor`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt:57):
```kotlin
.header("X-Plex-Client-Identifier", plexPrefsRepo.uuid)
```

The `X-Plex-Client-Identifier` identifies the **device** (persisted across app launches), while `X-Plex-Session-Identifier` identifies the **current session** (random per launch). Plex uses the Client Identifier to correlate:
- Play queue creation (POST `/playQueues`)
- Timeline updates (GET `/:/timeline`)

If these don't match, Plex won't associate timeline updates with the active play queue, causing 404 errors.

## Dependency Injection Integration

### AppModule Changes

Add provider method to [`AppModule`](../../app/src/main/java/local/oss/chronicle/injection/modules/AppModule.kt):

```kotlin
@Provides
@Singleton
fun scopedPlexServiceFactory(
    factory: ScopedPlexServiceFactory
): ScopedPlexServiceFactory = factory
```

**Note**: Constructor injection already works due to `@Inject` on `ScopedPlexServiceFactory`, but explicit provider documents the dependency in the module.

### Dependencies

```kotlin
@Inject constructor(
    private val moshi: Moshi,                    // From AppModule.moshi()
    private val plexPrefsRepo: PlexPrefsRepo,    // From AppModule.providePlexPrefsRepo()
    private val plexConfig: PlexConfig,          // Constructor-injected @Singleton
)
```

All dependencies are already `@Singleton` scoped in `AppComponent`, so no new modules needed.

## Consumer Changes

### PlaybackUrlResolver

**Before**:
```kotlin
@Singleton
class PlaybackUrlResolver @Inject constructor(
    private val plexMediaService: PlexMediaService,
    private val plexConfig: PlexConfig,
    private val serverConnectionResolver: ServerConnectionResolver,
) {
    // ...
    
    private suspend fun resolveUrlInternal(...): String {
        val connection = serverConnectionResolver.resolve(libraryId)
        val scopedService = createScopedService(connection)  // Duplicated
        val decision = scopedService.getPlaybackDecision(...)
        // ...
    }
    
    private fun createScopedService(connection: ServerConnection): PlexMediaService {
        // 30 lines of duplicated code with bare Moshi bug
    }
    
    private fun createScopedInterceptor(authToken: String): Interceptor {
        // 20 lines of duplicated code with incomplete headers
    }
}
```

**After**:
```kotlin
@Singleton
class PlaybackUrlResolver @Inject constructor(
    private val plexMediaService: PlexMediaService,
    private val plexConfig: PlexConfig,
    private val serverConnectionResolver: ServerConnectionResolver,
    private val scopedPlexServiceFactory: ScopedPlexServiceFactory,  // NEW
) {
    // ...
    
    private suspend fun resolveUrlInternal(...): String {
        val connection = serverConnectionResolver.resolve(libraryId)
        val scopedService = scopedPlexServiceFactory.getOrCreateService(connection)  // CHANGED
        val decision = scopedService.getPlaybackDecision(...)
        // ...
    }
    
    // Remove createScopedService() - 30 lines deleted
    // Remove createScopedInterceptor() - 20 lines deleted
}
```

**Lines removed**: ~50

### PlexProgressReporter

**Before**:
```kotlin
@Singleton
class PlexProgressReporter @Inject constructor(
    private val plexConfig: PlexConfig,
    private val plexPrefsRepo: PlexPrefsRepo,
    private val serverConnectionResolver: ServerConnectionResolver,
    private val libraryRepository: LibraryRepository,
) {
    // ...
    
    suspend fun reportProgress(...) {
        val service = createScopedService(connection)  // Duplicated
        service.progress(...)
    }
    
    suspend fun startMediaSession(...) {
        val service = createScopedService(connection)  // Duplicated
        val response = service.startMediaSession(uri)  // FAILS due to bare Moshi
        // ...
    }
    
    private fun createScopedService(connection: ServerConnection): PlexMediaService {
        // 30 lines of duplicated code with bare Moshi bug
    }
    
    private fun createScopedInterceptor(authToken: String): Interceptor {
        // 30 lines of duplicated code
    }
}
```

**After**:
```kotlin
@Singleton
class PlexProgressReporter @Inject constructor(
    private val plexConfig: PlexConfig,
    private val plexPrefsRepo: PlexPrefsRepo,
    private val serverConnectionResolver: ServerConnectionResolver,
    private val libraryRepository: LibraryRepository,
    private val scopedPlexServiceFactory: ScopedPlexServiceFactory,  // NEW
) {
    // ...
    
    suspend fun reportProgress(...) {
        val service = scopedPlexServiceFactory.getOrCreateService(connection)  // CHANGED
        service.progress(...)
    }
    
    suspend fun startMediaSession(...) {
        val service = scopedPlexServiceFactory.getOrCreateService(connection)  // CHANGED
        val response = service.startMediaSession(uri)  // NOW WORKS! ✅
        // ...
    }
    
    // Remove createScopedService() - 30 lines deleted
    // Remove createScopedInterceptor() - 30 lines deleted
}
```

**Lines removed**: ~60

**Total code removed**: ~110 lines of duplicated, buggy code

## Cache Management Strategy

### When to Cache

Services are cached on first use per `(serverUrl, authToken)` combination. Subsequent calls with the same connection reuse the cached instance.

### Cache Invalidation Scenarios

| Scenario | Action | Trigger Point |
|----------|--------|---------------|
| User logs out | `clearCache()` | [`PlexConfig.clear()`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexConfig.kt:338-343) |
| Account switched | `clearCache()` | Account switching logic in AccountManager |
| Auth token refresh | `clearCache()` | Token refresh in PlexLoginRepo |
| Server connections change | `clearCache()` | Server selection in login flow |

### Cache Size Considerations

**Typical size**: 1-3 entries per user session
- Most users: 1 account → 1 server → 1 cache entry
- Multi-library users: 1 account → 1 server → 1 cache entry (same serverUrl + authToken)
- Multi-account users: N accounts → N servers → N cache entries

**Memory impact**: Each entry holds:
- Retrofit instance: ~few KB
- OkHttpClient: ~10-20 KB (connection pool, etc.)
- PlexMediaService proxy: ~1 KB

**Total per entry**: ~30 KB → 3 entries = ~90 KB (negligible)

**No auto-eviction needed**: Cache is manually cleared on account/server changes, and memory impact is minimal.

## Testing Strategy

### Unit Tests

Create [`ScopedPlexServiceFactoryTest.kt`](../../app/src/test/java/local/oss/chronicle/data/sources/plex/ScopedPlexServiceFactoryTest.kt):

```kotlin
class ScopedPlexServiceFactoryTest {
    @Test
    fun `getOrCreateService caches instances for same connection`() {
        // Given: same server URL and token
        // When: call getOrCreateService twice
        // Then: returns same instance (reference equality)
    }
    
    @Test
    fun `getOrCreateService creates new instance for different serverUrl`() {
        // Given: different server URLs, same token
        // When: call getOrCreateService for each
        // Then: returns different instances
    }
    
    @Test
    fun `getOrCreateService creates new instance for different authToken`() {
        // Given: same server URL, different tokens
        // When: call getOrCreateService for each
        // Then: returns different instances
    }
    
    @Test
    fun `clearCache removes all cached instances`() {
        // Given: multiple cached services
        // When: clearCache() called
        // Then: subsequent calls create new instances
    }
    
    @Test
    fun `scoped interceptor includes all required headers`() {
        // Verify header consistency with PlexInterceptor
    }
    
    @Test
    fun `uses properly configured Moshi with KotlinJsonAdapterFactory`() {
        // Verify Moshi can deserialize Kotlin data classes
    }
}
```

### Integration Tests

Update existing tests:

**PlaybackUrlResolverTest**:
- Verify scoped service is called correctly
- Mock `scopedPlexServiceFactory.getOrCreateService()`

**PlexProgressReporterTest**:
- Verify `startMediaSession()` now succeeds (was failing before)
- Verify `playQueueItemCache` is populated
- Verify timeline updates succeed (no more 404s)

## Benefits

### 1. Fixes Critical Bugs

- ✅ `startMediaSession()` now works (proper Moshi deserialization)
- ✅ Play queue IDs are captured and cached
- ✅ Timeline updates work (no more 404s)
- ✅ Plex dashboard shows playback activity

### 2. Code Quality

- ✅ Eliminates ~110 lines of duplicated code
- ✅ Single source of truth for scoped service creation
- ✅ Consistent headers across all scoped requests
- ✅ Proper dependency injection pattern

### 3. Performance

- ✅ Caching reduces Retrofit/OkHttp instance creation overhead
- ✅ Connection pooling within each cached client
- ✅ Thread-safe concurrent access without locking

### 4. Maintainability

- ✅ Future changes to scoped service logic happen in one place
- ✅ Header changes propagate to all consumers automatically
- ✅ Testable in isolation from consumers

## Migration Checklist

- [ ] Create [`ScopedPlexServiceFactory.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/ScopedPlexServiceFactory.kt)
- [ ] Add provider to [`AppModule.kt`](../../app/src/main/java/local/oss/chronicle/injection/modules/AppModule.kt)
- [ ] Update [`PlaybackUrlResolver.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlaybackUrlResolver.kt)
  - [ ] Add `scopedPlexServiceFactory` dependency
  - [ ] Replace `createScopedService()` calls
  - [ ] Remove `createScopedService()` method
  - [ ] Remove `createScopedInterceptor()` method
- [ ] Update [`PlexProgressReporter.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexProgressReporter.kt)
  - [ ] Add `scopedPlexServiceFactory` dependency
  - [ ] Replace `createScopedService()` calls
  - [ ] Remove `createScopedService()` method
  - [ ] Remove `createScopedInterceptor()` method
- [ ] Update [`PlexConfig.clear()`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexConfig.kt) to call `scopedPlexServiceFactory.clearCache()`
- [ ] Write unit tests for `ScopedPlexServiceFactory`
- [ ] Update integration tests to verify bug fixes
- [ ] Manual testing:
  - [ ] Verify `startMediaSession()` succeeds
  - [ ] Verify Plex dashboard shows playback activity
  - [ ] Verify timeline updates work
  - [ ] Test multi-library playback
  - [ ] Test account switching

## References

- [`PlaybackUrlResolver.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlaybackUrlResolver.kt) — Current duplicated implementation
- [`PlexProgressReporter.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexProgressReporter.kt) — Current duplicated implementation
- [`PlexInterceptor.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt) — Header reference
- [`AppModule.kt`](../../app/src/main/java/local/oss/chronicle/injection/modules/AppModule.kt) — DI configuration
- [`ServerConnectionResolver.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/ServerConnectionResolver.kt) — Resolves library connections
- [`docs/architecture/plex-dashboard-activity.md`](plex-dashboard-activity.md) — Play queue requirement context
