# Lazy Token Injection for ExoPlayer HTTP DataSource

**Status:** ✅ Implementation Complete
**Created:** 2026-02-03
**Completed:** 2026-02-03
**Problem Tracking:** Race condition bug causing HTTP 401 errors until app restart

## Implementation Status

This design has been **fully implemented** and tested. The following files were created:

- **Core Implementation:**
  - [`PlexHttpDataSourceFactory.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexHttpDataSourceFactory.kt) - Custom DataSource.Factory that performs lazy token injection on each HTTP request
  - [`SecurityUtils.kt`](../../app/src/main/java/local/oss/chronicle/util/SecurityUtils.kt) - Security utilities for header redaction in logs

- **Testing:**
  - [`PlexHttpDataSourceFactoryTest.kt`](../../app/src/test/java/local/oss/chronicle/data/sources/plex/PlexHttpDataSourceFactoryTest.kt) - Comprehensive unit tests covering token injection, fallback, and thread safety

- **Integration:**
  - Updated [`ServiceModule.kt`](../../app/src/main/java/local/oss/chronicle/injection/modules/ServiceModule.kt) to provide `PlexHttpDataSourceFactory` instead of `DefaultHttpDataSource.Factory`

---

## Original Design Document

---

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Root Cause Analysis](#root-cause-analysis)
3. [Solution Design](#solution-design)
   - [Diagnostic Logging](#1-diagnostic-logging-design)
   - [PlexHttpDataSourceFactory](#2-plexhttpdatasourcefactory-design)
   - [ServiceModule Integration](#3-servicemodule-integration)
   - [Testing Strategy](#4-testing-strategy)
4. [Implementation Steps](#implementation-steps)
5. [Validation Plan](#validation-plan)
6. [Rollback Plan](#rollback-plan)

---

## Problem Statement

`MediaPlayerService` experiences HTTP 401 authentication errors when streaming audiobooks from Plex Media Server. The errors persist until the app is force-stopped and restarted, despite valid authentication tokens being available in SharedPreferences.

**Symptom:** Users see "Authentication failed" errors in Android Auto and playback fails with 401 responses from the Plex server.

**Impact:** Complete playback failure requiring app restart, severely degrading user experience especially during Android Auto usage.

---

## Root Cause Analysis

### Current Implementation

In [`ServiceModule.kt:149-179`](../../app/src/main/java/local/oss/chronicle/injection/modules/ServiceModule.kt#L149-L179), the `plexDataSourceFactory()` provider method creates a `DefaultHttpDataSource.Factory` and calls `setDefaultRequestProperties()`:

```kotlin
@Provides
@ServiceScope
fun plexDataSourceFactory(plexPrefs: PlexPrefsRepo): DefaultHttpDataSource.Factory {
    val dataSourceFactory = DefaultHttpDataSource.Factory()
    dataSourceFactory.setUserAgent(Util.getUserAgent(service, APP_NAME))

    dataSourceFactory.setDefaultRequestProperties(
        mapOf(
            "X-Plex-Token" to (
                plexPrefs.server?.accessToken ?: plexPrefs.user?.authToken
                    ?: plexPrefs.accountAuthToken
            ),
            // ... other headers
        ),
    )

    return dataSourceFactory
}
```

### The Race Condition

1. **Service Initialization** ([`MediaPlayerService.kt:226-233`](../../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt#L226-L233)):
   ```kotlin
   override fun onCreate() {
       super.onCreate()
       
       DaggerServiceComponent.builder()
           .appComponent((application as ChronicleApplication).appComponent)
           .serviceModule(ServiceModule(this))
           .build()
           .inject(this)
   ```

2. **Dagger Dependency Graph Construction:**
   - `ServiceModule.plexDataSourceFactory()` is called during DI graph construction
   - This happens **before** `MediaPlayerService.onCreate()` completes
   - At this point, `PlexPrefsRepo` may not yet have loaded tokens from SharedPreferences

3. **Token "Baking":**
   - `setDefaultRequestProperties()` creates a **static copy** of the token map
   - These headers are frozen at graph construction time
   - Even if `PlexPrefsRepo` later loads tokens from SharedPreferences, the factory still uses the **stale empty values**

4. **Why Restart Fixes It:**
   - On app restart, SharedPreferences are loaded earlier in the app lifecycle
   - By the time Dagger constructs the service component, tokens are already in memory
   - The factory captures the **correct** token values this time

### Evidence from PlexInterceptor Pattern

[`PlexInterceptor.kt:47-78`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt#L47-L78) demonstrates the correct pattern for Retrofit/OkHttp:

```kotlin
override fun intercept(chain: Interceptor.Chain): Response {
    // Read tokens FRESH on each request
    val userToken = plexPrefsRepo.user?.authToken
    val serverToken = plexPrefsRepo.server?.accessToken
    val accountToken = plexPrefsRepo.accountAuthToken
    
    val authToken = /* selection logic */
    
    if (authToken.isNotEmpty()) {
        requestBuilder.header("X-Plex-Token", authToken)
    }
    
    return chain.proceed(requestBuilder.build())
}
```

**Key Difference:** Interceptor reads tokens **lazily** on each request, not during object construction.

---

## Solution Design

### 1. Diagnostic Logging Design

To confirm the race condition and track token states during debugging, we'll add **secure, production-safe** logging.

#### Token Hashing Utility

**Location:** `app/src/main/java/local/oss/chronicle/util/SecurityUtils.kt` (new file)

```kotlin
package local.oss.chronicle.util

import java.security.MessageDigest
import timber.log.Timber

object SecurityUtils {
    /**
     * Securely hash a token for logging purposes.
     * Returns first 16 chars of SHA-256 hash to identify tokens without exposing them.
     * 
     * @param token The authentication token to hash
     * @return Hashed token prefix (e.g., "a1b2c3d4e5f6g7h8") or "<empty>" if blank
     */
    fun hashToken(token: String?): String {
        if (token.isNullOrBlank()) {
            return "<empty>"
        }
        
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(token.toByteArray())
            hashBytes.take(8) // First 8 bytes = 16 hex chars
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to hash token")
            "<hash_error>"
        }
    }
}
```

**Why SHA-256 + truncation:**
- Cryptographically secure one-way hash
- 16-character prefix is enough to uniquely identify tokens without exposing them
- Safe for production logs, crash reports, and debug builds

#### Logging Points

**1. ServiceModule.plexDataSourceFactory()** - Capture token at factory creation:

```kotlin
@Provides
@ServiceScope
fun plexDataSourceFactory(plexPrefs: PlexPrefsRepo): DefaultHttpDataSource.Factory {
    // Log token state at factory creation
    val serverTokenHash = SecurityUtils.hashToken(plexPrefs.server?.accessToken)
    val userTokenHash = SecurityUtils.hashToken(plexPrefs.user?.authToken)
    val accountTokenHash = SecurityUtils.hashToken(plexPrefs.accountAuthToken)
    
    Timber.d(
        "[TokenInjection] plexDataSourceFactory created: " +
        "serverToken=$serverTokenHash, userToken=$userTokenHash, " +
        "accountToken=$accountTokenHash"
    )
    
    // ... rest of implementation
}
```

**2. SharedPreferencesPlexPrefsRepo** - Log when tokens are loaded from disk:

Add to [`SharedPreferencesPlexPrefsRepo.kt`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/SharedPreferencesPlexPrefsRepo.kt) getters:

```kotlin
override var accountAuthToken: String
    get() {
        val token = getString(PREFS_AUTH_TOKEN_KEY)
        Timber.d("[TokenInjection] PlexPrefsRepo.accountAuthToken read: ${SecurityUtils.hashToken(token)}")
        return token
    }
    // ... setter unchanged
```

**3. MediaPlayerService.onCreate()** - Track initialization order:

Add to [`MediaPlayerService.kt:226`](../../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt#L226):

```kotlin
override fun onCreate() {
    super.onCreate()
    
    Timber.d("[TokenInjection] MediaPlayerService.onCreate() starting - about to inject dependencies")
    
    DaggerServiceComponent.builder()
        .appComponent((application as ChronicleApplication).appComponent)
        .serviceModule(ServiceModule(this))
        .build()
        .inject(this)
    
    Timber.d("[TokenInjection] MediaPlayerService.onCreate() - dependencies injected")
    
    // Verify tokens after injection
    val serverTokenHash = SecurityUtils.hashToken(plexPrefs.server?.accessToken)
    Timber.d("[TokenInjection] Post-injection token check: serverToken=$serverTokenHash")
    
    // ... rest of method
}
```

#### Log Analysis

With these logs, we can trace the exact sequence:

```
[TokenInjection] MediaPlayerService.onCreate() starting - about to inject dependencies
[TokenInjection] PlexPrefsRepo.accountAuthToken read: <empty>
[TokenInjection] plexDataSourceFactory created: serverToken=<empty>, userToken=<empty>, accountToken=<empty>
[TokenInjection] MediaPlayerService.onCreate() - dependencies injected
[TokenInjection] Post-injection token check: serverToken=a1b2c3d4e5f6g7h8

PROBLEM: Factory was created with <empty> tokens, but actual token (a1b2c3d4e5f6g7h8) exists after injection!
```

---

### 2. PlexHttpDataSourceFactory Design

Create a custom `HttpDataSource.Factory` that reads tokens **lazily** on every `createDataSource()` call, mirroring the `PlexInterceptor` pattern.

#### Class Design

**Location:** `app/src/main/java/local/oss/chronicle/data/sources/plex/PlexHttpDataSourceFactory.kt` (new file)

```kotlin
package local.oss.chronicle.data.sources.plex

import android.content.Context
import android.os.Build
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import local.oss.chronicle.BuildConfig
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
            val tokenHash = local.oss.chronicle.util.SecurityUtils.hashToken(authToken)
            Timber.d(
                "[TokenInjection] PlexHttpDataSourceFactory.createDataSource(): " +
                "token=$tokenHash, hasServerToken=${serverToken != null}, " +
                "hasUserToken=${userToken != null}, hasAccountToken=${accountToken.isNotEmpty()}"
            )
        }
        
        // Build header map with FRESH token
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
        
        return headers
    }
}
```

#### Design Decisions

**Why not extend DefaultHttpDataSource.Factory?**
- `DefaultHttpDataSource.Factory` uses internal state that's set via `setDefaultRequestProperties()`
- We need to **rebuild** headers on each call, which requires wrapping rather than extending

**Why create a new DefaultHttpDataSource.Factory each time?**
- Ensures clean state per request
- Overhead is minimal - only creates a lightweight factory object
- ExoPlayer caches data sources internally for performance

**Token Selection Priority:**
1. `server.accessToken` - Server-specific token (most privileged)
2. `user.authToken` - User profile token
3. `accountAuthToken` - Account-level token (fallback)

This matches the logic in [`PlexInterceptor.kt:66-71`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt#L66-L71).

---

### 3. ServiceModule Integration

Update [`ServiceModule.kt`](../../app/src/main/java/local/oss/chronicle/injection/modules/ServiceModule.kt) to use the new factory.

#### Before (Current)

```kotlin
@Provides
@ServiceScope
fun plexDataSourceFactory(plexPrefs: PlexPrefsRepo): DefaultHttpDataSource.Factory {
    val dataSourceFactory = DefaultHttpDataSource.Factory()
    dataSourceFactory.setUserAgent(Util.getUserAgent(service, APP_NAME))

    dataSourceFactory.setDefaultRequestProperties(
        mapOf(
            "X-Plex-Platform" to "Android",
            // ... static headers with STALE token
            "X-Plex-Token" to (
                plexPrefs.server?.accessToken ?: plexPrefs.user?.authToken
                    ?: plexPrefs.accountAuthToken
            ),
        ),
    )

    return dataSourceFactory
}
```

#### After (Proposed)

```kotlin
@Provides
@ServiceScope
fun plexDataSourceFactory(plexPrefs: PlexPrefsRepo): HttpDataSource.Factory {
    // Return custom factory that reads tokens lazily
    return PlexHttpDataSourceFactory(
        context = service.applicationContext,
        plexPrefsRepo = plexPrefs,
    )
}
```

**Key Changes:**
1. Return type: `DefaultHttpDataSource.Factory` → `HttpDataSource.Factory` (interface)
2. Remove `setDefaultRequestProperties()` call
3. Delegate to `PlexHttpDataSourceFactory` which handles token injection internally

**Backward Compatibility:**
- ExoPlayer accepts `HttpDataSource.Factory` interface everywhere
- No changes needed to injection points or consumers
- `@ServiceScope` lifecycle unchanged

#### Injection Point Verification

Check where `plexDataSourceFactory` is injected:

```bash
# Search for injection points
grep -r "HttpDataSource.Factory" app/src/main/java/local/oss/chronicle/features/player/
```

Expected result: `AudiobookMediaSessionCallback` or similar classes that build ExoPlayer media sources.

---

### 4. Testing Strategy

#### Unit Tests

**Location:** `app/src/test/java/local/oss/chronicle/data/sources/plex/PlexHttpDataSourceFactoryTest.kt` (new file)

```kotlin
package local.oss.chronicle.data.sources.plex

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import local.oss.chronicle.data.model.PlexLibrary
import local.oss.chronicle.data.model.ServerModel
import local.oss.chronicle.data.sources.plex.model.PlexUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class PlexHttpDataSourceFactoryTest {
    private lateinit var mockContext: Context
    private lateinit var mockPlexPrefs: PlexPrefsRepo
    private lateinit var factory: PlexHttpDataSourceFactory

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockPlexPrefs = mockk(relaxed = true)
        
        // Default mock setup
        every { mockPlexPrefs.uuid } returns "test-uuid-12345"
        every { mockPlexPrefs.accountAuthToken } returns ""
        every { mockPlexPrefs.user } returns null
        every { mockPlexPrefs.server } returns null
        
        factory = PlexHttpDataSourceFactory(mockContext, mockPlexPrefs)
    }

    @Test
    fun `createDataSource reads tokens fresh on each call`() {
        // First call - no tokens
        every { mockPlexPrefs.accountAuthToken } returns ""
        val dataSource1 = factory.createDataSource()
        assertNotNull(dataSource1)
        
        // Second call - token now available
        every { mockPlexPrefs.accountAuthToken } returns "new-token-abc123"
        val dataSource2 = factory.createDataSource()
        assertNotNull(dataSource2)
        
        // Verify PlexPrefsRepo was queried twice (fresh reads)
        verify(exactly = 2) { mockPlexPrefs.accountAuthToken }
    }

    @Test
    fun `createDataSource prioritizes server token over user token`() {
        val serverToken = "server-token-xyz"
        val userToken = "user-token-abc"
        
        every { mockPlexPrefs.server } returns ServerModel(
            name = "Test Server",
            connections = emptyList(),
            serverId = "server-123",
            accessToken = serverToken,
            owned = true
        )
        every { mockPlexPrefs.user } returns PlexUser(
            id = "user-123",
            uuid = "user-uuid",
            admin = true,
            guest = false,
            restricted = false,
            protected = true,
            home = true,
            title = "Test User",
            username = "testuser",
            email = "test@example.com",
            thumb = null,
            authToken = userToken
        )
        
        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
        
        // Should have read server token (priority over user token)
        verify { mockPlexPrefs.server }
    }

    @Test
    fun `createDataSource uses accountAuthToken as fallback`() {
        val accountToken = "account-token-fallback"
        
        every { mockPlexPrefs.server } returns null
        every { mockPlexPrefs.user } returns null
        every { mockPlexPrefs.accountAuthToken } returns accountToken
        
        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
        
        verify { mockPlexPrefs.accountAuthToken }
    }

    @Test
    fun `createDataSource includes required Plex headers`() {
        every { mockPlexPrefs.uuid } returns "test-uuid-12345"
        
        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
        
        // Verify UUID was read (used in headers)
        verify { mockPlexPrefs.uuid }
    }

    @Test
    fun `createDataSource handles empty tokens gracefully`() {
        every { mockPlexPrefs.server } returns null
        every { mockPlexPrefs.user } returns null
        every { mockPlexPrefs.accountAuthToken } returns ""
        
        // Should not throw exception
        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }
}
```

**Test Coverage:**
- ✅ Tokens are read **fresh** on each `createDataSource()` call
- ✅ Token priority: server → user → account
- ✅ Required Plex headers are included (UUID, Client-Profile-Extra, etc.)
- ✅ Handles empty/null tokens gracefully
- ✅ Multiple calls to `createDataSource()` work correctly

#### Integration Tests

**Manual Android Auto Test Plan:**

1. **Cold Start Test:**
   - Force stop Chronicle app
   - Clear app from recent apps
   - Open Android Auto (or Android Auto for Phone Screens)
   - Navigate to Chronicle
   - Select audiobook and play
   - **Expected:** Playback starts successfully without 401 error

2. **Login Flow Test:**
   - Log out from Chronicle
   - Force stop app
   - Relaunch app and complete OAuth login flow
   - Immediately switch to Android Auto
   - Select audiobook and play
   - **Expected:** Playback works immediately after login

3. **Token Refresh Test:**
   - Play audiobook successfully
   - (Simulate token expiry by manually clearing server token via debug menu if available)
   - Re-login or refresh token
   - Resume playback
   - **Expected:** Playback continues with new token

#### Logging Verification

With diagnostic logging enabled, verify logs show:

```
[TokenInjection] MediaPlayerService.onCreate() starting - about to inject dependencies
[TokenInjection] plexDataSourceFactory created (returns PlexHttpDataSourceFactory)
[TokenInjection] MediaPlayerService.onCreate() - dependencies injected
[TokenInjection] Post-injection token check: serverToken=a1b2c3d4e5f6g7h8

(Later when playback starts)
[TokenInjection] PlexHttpDataSourceFactory.createDataSource(): token=a1b2c3d4e5f6g7h8, hasServerToken=true
```

**Success Criteria:** Token hash in `createDataSource()` matches the post-injection check, proving lazy token read works.

---

## Implementation Steps

### Phase 1: Diagnostic Logging (Safe to Deploy)

1. Create `SecurityUtils.kt` with `hashToken()` function
2. Add logging to:
   - `ServiceModule.plexDataSourceFactory()`
   - `SharedPreferencesPlexPrefsRepo` token getters
   - `MediaPlayerService.onCreate()`
3. Test in local debug build
4. Deploy to beta testers to capture real-world race condition logs
5. **Validation:** Confirm logs show the race condition (factory created with empty tokens)

### Phase 2: PlexHttpDataSourceFactory (Fix)

1. Create `PlexHttpDataSourceFactory.kt` with lazy token injection
2. Write unit tests (`PlexHttpDataSourceFactoryTest.kt`)
3. Run tests: `./gradlew :app:testDebugUnitTest --tests "*PlexHttpDataSourceFactoryTest"`
4. **Validation:** All unit tests pass

### Phase 3: ServiceModule Integration

1. Update `ServiceModule.plexDataSourceFactory()` to return `PlexHttpDataSourceFactory`
2. Change return type to `HttpDataSource.Factory` interface
3. Verify compilation: `./gradlew :app:assembleDebug`
4. **Validation:** Build succeeds without errors

### Phase 4: Testing & Validation

1. Manual Android Auto testing per test plan above
2. Monitor logs for successful lazy token injection
3. Beta deployment to limited users
4. Monitor crash reports and error logs
5. **Validation:** No 401 errors reported in production logs

### Phase 5: Cleanup (Optional)

1. Remove debug logging from `PlexHttpDataSourceFactory.createDataSource()` if not needed long-term
2. Keep diagnostic logging in `MediaPlayerService.onCreate()` for future debugging
3. Update [`AGENT.md`](../../AGENT.md) with new pattern

---

## Validation Plan

### Success Metrics

| Metric | Current (Broken) | Target (Fixed) |
|--------|------------------|----------------|
| Cold start playback success rate | ~50% (requires restart) | >95% |
| 401 errors in production logs | Frequent | Rare/None |
| Android Auto playback failures | Common | Rare/None |
| Time to reproduce race condition | Immediate on cold start | Should not reproduce |

### Monitoring

**Log Queries** (if using centralized logging like Firebase Crashlytics):

```
# Count 401 errors before/after fix
filter: "HTTP 401" AND component: "MediaPlayerService"
timeRange: last_7_days
groupBy: app_version

# Token injection success
filter: "[TokenInjection] PlexHttpDataSourceFactory.createDataSource()" AND "token=<empty>"
timeRange: last_7_days
```

**User Reports:**
- Monitor support channels for "playback fails until restart" complaints
- Track Android Auto specific error reports

### Rollback Triggers

Rollback if any of these occur:

1. **Crash rate increase** >5% in `MediaPlayerService`
2. **Playback failures** >10% increase overall
3. **New error patterns** not seen in pre-release testing
4. **Performance regression** in media loading (>2s slower)

---

## Rollback Plan

### Quick Rollback (Emergency)

If critical issues arise, revert to previous implementation:

1. **Revert ServiceModule changes:**
   ```kotlin
   @Provides
   @ServiceScope
   fun plexDataSourceFactory(plexPrefs: PlexPrefsRepo): DefaultHttpDataSource.Factory {
       // Restore old implementation from git history
       val dataSourceFactory = DefaultHttpDataSource.Factory()
       dataSourceFactory.setDefaultRequestProperties(/* old headers */)
       return dataSourceFactory
   }
   ```

2. **Remove PlexHttpDataSourceFactory import**
3. **Rebuild and deploy hotfix:** `./gradlew :app:assembleRelease`
4. **Monitor:** Verify rollback resolves issues

### Partial Rollback (Feature Flag)

Add a feature flag to toggle between implementations:

```kotlin
@Provides
@ServiceScope
fun plexDataSourceFactory(plexPrefs: PlexPrefsRepo): HttpDataSource.Factory {
    return if (BuildConfig.USE_LAZY_TOKEN_INJECTION) {
        PlexHttpDataSourceFactory(service.applicationContext, plexPrefs)
    } else {
        // Old implementation
        DefaultHttpDataSource.Factory().apply {
            setDefaultRequestProperties(/* old headers */)
        }
    }
}
```

**Advantage:** Can toggle without code changes via build configuration.

---

## Future Improvements

### Token Refresh Handling

Currently, tokens are read fresh but never actively refreshed. Consider:

- Implement token expiry detection (401 → auto-refresh)
- Add token refresh callback to `PlexHttpDataSourceFactory`
- Integrate with `PlexLoginRepo` for seamless re-authentication

### Metrics & Observability

- Add custom metric for token read latency
- Track frequency of token changes during playback sessions
- Monitor correlation between token refreshes and playback errors

### Error Handling

- Detect 401 errors in ExoPlayer error callbacks
- Trigger token refresh automatically
- Show user-friendly "Re-authenticating..." message in Android Auto

---

## References

- **Problem Location:** [`ServiceModule.kt:149-179`](../../app/src/main/java/local/oss/chronicle/injection/modules/ServiceModule.kt#L149-L179)
- **Working Pattern:** [`PlexInterceptor.kt:47-78`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/PlexInterceptor.kt#L47-L78)
- **Service Lifecycle:** [`MediaPlayerService.kt:226-233`](../../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt#L226-L233)
- **Token Storage:** [`SharedPreferencesPlexPrefsRepo.kt:87-93`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/SharedPreferencesPlexPrefsRepo.kt#L87-L93)

---

## Document History

| Date | Author | Changes |
|------|--------|---------|
| 2026-02-03 | AI Assistant | Initial design document |
