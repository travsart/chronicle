# Android Auto Voice Command Testing Guide

This document describes the comprehensive test suite that validates Android Auto and Google Assistant voice commands for Chronicle.

## Overview

### Voice Commands Supported
Google Assistant automatically handles these voice commands when connected to Chronicle:
- "Resume playing my audiobook" → `ACTION_PLAY`
- "Continue my audiobook" → `ACTION_PLAY`
- "Pause" → `ACTION_PAUSE`
- "Skip back 30 seconds" → seek operations
- "Play audiobook X on Chronicle" → `PLAY_FROM_SEARCH`

### What Cannot Be Tested
- Actual voice recognition (cloud-side)
- Google Assistant phrase → intent mapping
- App name / nickname recognition

### What CAN and MUST Be Tested
If these invariants are validated, voice commands WILL work:
- ✅ MediaBrowserService is accessible
- ✅ MediaSession is active with correct state
- ✅ PlaybackState reports correct state and actions
- ✅ Transport controls work correctly
- ✅ Resume continues from last position, not beginning

## Test Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Manual Smoke Test                              │
│              (Required: Actual voice on device)                      │
├─────────────────────────────────────────────────────────────────────┤
│                  Instrumentation Tests (7 tests)                     │
│                 MediaBrowserTransportControlsTest                    │
│      Validates MediaBrowser connection + transport controls          │
├─────────────────────────────────────────────────────────────────────┤
│                   Robolectric Tests (11 tests)                       │
│                     MediaSessionStateTest                            │
│      Validates MediaSession state + PlaybackState actions            │
├─────────────────────────────────────────────────────────────────────┤
│                     JVM Unit Tests (39+ tests)                       │
│   PlaybackResumeLogicTest + AudiobookMediaSessionCallbackTest        │
│      Validates resume logic, position persistence, error handling    │
└─────────────────────────────────────────────────────────────────────┘
```

## Test Files

### 1. JVM Unit Tests

#### [`PlaybackResumeLogicTest.kt`](../../app/src/test/java/local/oss/chronicle/features/player/PlaybackResumeLogicTest.kt)
**Tests:** 14 | **Focus:** Resume/playback state logic

| Test | Voice Command Validated |
|------|------------------------|
| Resume starts from last saved position | "Resume playing my audiobook" |
| Position preserved after pause | "Pause" / "Resume" cycle |
| Multiple pause/play cycles maintain position | Continuous use |
| State transitions correctly | All transport controls |

#### [`AudiobookMediaSessionCallbackTest.kt`](../../app/src/test/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallbackTest.kt)
**Tests:** 25 | **Focus:** MediaSession callback handling

| Test | Voice Command Validated |
|------|------------------------|
| onPlayFromSearch with valid query | "Play [audiobook name]" |
| onPlayFromSearch with empty query (fallback) | "Play something" |
| Authentication error handling | Connection state errors |
| onSeekTo chapter conversion | "Skip back/forward" |

**Key tests for voice command error handling:**
- `onPlayFromSearch shows error when no results found for specific query` - Validates `ERROR_CODE_NOT_AVAILABLE_IN_REGION` (7)
- `onPlayFromSearch shows library empty error on fallback failure` - Validates graceful degradation
- `onPlayFromSearch handles exceptions and reports error` - Validates `ERROR_CODE_APP_ERROR` (1)

### 2. Robolectric Tests

#### [`MediaSessionStateTest.kt`](../../app/src/test/java/local/oss/chronicle/features/player/MediaSessionStateTest.kt)
**Tests:** 11 | **Focus:** MediaSession state validation

| Test | Voice Command Validated |
|------|------------------------|
| Paused session exposes ACTION_PLAY | "Resume" eligibility |
| Session correctly reports STATE_PAUSED | Resume vs restart |
| Metadata exposed for audiobook | Content identification |
| Session supports essential actions | All transport controls |

### 3. Instrumentation Tests

#### [`MediaBrowserTransportControlsTest.kt`](../../app/src/androidTest/java/local/oss/chronicle/features/player/MediaBrowserTransportControlsTest.kt)
**Tests:** 7 | **Focus:** End-to-end MediaBrowser integration

| Test | What It Validates |
|------|-------------------|
| Manifest declares automotive media support | Android Auto discovery |
| Manifest declares MediaBrowserService | Service availability |
| MediaBrowser can connect | Android Auto connection |
| Session token provided | Controller creation |
| Transport controls available | Voice command execution |

## Running the Tests

### All Unit Tests (CI Pipeline)
```bash
./gradlew test
```

### Specific Test Files
```bash
# Resume logic tests
./gradlew :app:testDebugUnitTest --tests "*.PlaybackResumeLogicTest"

# MediaSession state tests
./gradlew :app:testDebugUnitTest --tests "*.MediaSessionStateTest"

# MediaSession callback tests
./gradlew :app:testDebugUnitTest --tests "*.AudiobookMediaSessionCallbackTest"
```

### Instrumentation Tests (Requires Device/Emulator)
```bash
./gradlew :app:connectedDebugAndroidTest --tests "*.MediaBrowserTransportControlsTest"
```

## Voice Command Validation Matrix

| Voice Command | JVM Unit | Robolectric | Instrumentation | Manual |
|--------------|----------|-------------|-----------------|--------|
| "Resume my audiobook" | ✅ | ✅ | ✅ | Required |
| "Continue my audiobook" | ✅ | ✅ | ✅ | Required |
| "Pause" | ✅ | ✅ | ○ | Required |
| "Skip back 30 seconds" | ✅ | ○ | ○ | Required |
| "Play [title] on Chronicle" | ✅ | ○ | ○ | Required |

✅ = Covered | ○ = Not directly tested | Required = Must test manually

## Manual Smoke Test Checklist

Since actual voice recognition cannot be automated, perform these tests before release:

### Setup
- [ ] Install app on Android Auto-compatible device
- [ ] Connect to Android Auto (car or Desktop Head Unit)
- [ ] Ensure audiobook library is populated

### Basic Voice Commands
- [ ] Say "Hey Google, resume playing my audiobook"
  - [ ] Playback starts from last position (not beginning)
  - [ ] Correct audiobook resumes
- [ ] Say "Hey Google, pause"
  - [ ] Playback stops
  - [ ] Position is preserved
- [ ] Say "Hey Google, skip back 30 seconds"
  - [ ] Position moves backwards

### Search Commands
- [ ] Say "Hey Google, play [audiobook title] on Chronicle"
  - [ ] Correct audiobook starts playing
- [ ] Say "Hey Google, play audiobooks on Chronicle"
  - [ ] Recently listened audiobook starts

### Error Conditions
- [ ] Test when not logged in
  - [ ] Appropriate error message displayed
- [ ] Test when no audiobooks in library
  - [ ] Appropriate error message displayed

## Prerequisites for Voice Commands

For voice commands to work, the app must:

1. **Implement MediaBrowserServiceCompat**
   - Declared in AndroidManifest.xml with `android.media.browse.MediaBrowserService` intent filter
   - Exported for Android Auto to connect

2. **Declare Android Auto Support**
   - `<meta-data android:name="com.google.android.gms.car.application">` in manifest
   - `res/xml/automotive_app_desc.xml` with `<uses name="media"/>`

3. **Maintain Active MediaSession**
   - Session active when playing or paused
   - Correct PlaybackState (STATE_PLAYING, STATE_PAUSED)
   - Actions include ACTION_PLAY, ACTION_PAUSE, etc.

4. **Report Correct Metadata**
   - Title, album, duration for audiobook identification

## Lessons Learned

### Async Test Patterns
When testing async code with coroutines:

1. **Avoid nested coroutine launches** - Use flat suspend functions instead of `launch { launch { } }`
2. **Inject exception handlers** - Don't use global `Injector.get()` in testable code
3. **Use StandardTestDispatcher** - With `advanceUntilIdle()` for deterministic testing
4. **Make functions suspend** - Prefer `suspend fun` over `fun` that launches coroutines internally

**Example - Bad Pattern:**
```kotlin
private fun handleSearch(query: String?) {
    serviceScope.launch {  // outer
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {  // nested (problem!)
            // search logic
        }
    }
}
```

**Example - Good Pattern:**
```kotlin
override fun onPlayFromSearch(query: String?, extras: Bundle?) {
    serviceScope.launch(coroutineExceptionHandler) {  // Injected, not global
        try {
            handleSearchSuspend(query, true)  // Suspend function, not nested launch
        } catch (e: Exception) {
            onSetPlaybackError(ERROR_CODE_APP_ERROR, ...)
        }
    }
}

private suspend fun handleSearchSuspend(query: String?, playWhenReady: Boolean) {
    // Direct suspend calls - no nested launches
    val matchingBooks = bookRepository.searchAsync(query)
    // ...
}
```

### Error Code Propagation
Ensure error codes flow correctly through the error handling chain:

```
SearchException → handleSearchSuspend catch → setPlaybackStateError → Android Auto UI
```

Error codes must match what Android Auto expects:
- `ERROR_CODE_NOT_AVAILABLE_IN_REGION` (7) - No results found for specific query
- `ERROR_CODE_APP_ERROR` (1) - Generic application error
- `ERROR_CODE_AUTHENTICATION_EXPIRED` (3) - User needs to log in

### Testing Coroutine-Based Callbacks
When testing MediaSession callbacks that use coroutines:

1. **Use TestScope** - Inject a test scope instead of production scope for tests
2. **Control timing** - Use `testScheduler.advanceUntilIdle()` to execute all pending coroutines
3. **Mock dependencies** - Mock repositories and services that callbacks interact with
4. **Verify error paths** - Test both success and failure scenarios

**Example Test Setup:**
```kotlin
@ExperimentalCoroutinesApi
class AudiobookMediaSessionCallbackTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    
    private lateinit var testExceptionHandler: CoroutineExceptionHandler
    
    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        testExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "Test coroutine exception")
        }
        
        callback = AudiobookMediaSessionCallback(
            serviceScope = testScope,
            coroutineExceptionHandler = testExceptionHandler,
            // ... other mocked dependencies
        )
    }
    
    @Test
    fun `test voice command error handling`() = testScope.runTest {
        // Setup mocks
        coEvery { bookRepository.searchAsync(any()) } returns emptyList()
        
        // Execute
        callback.onPlayFromSearch("nonexistent", null)
        testScheduler.advanceUntilIdle()
        
        // Verify
        verify { errorReporter.setPlaybackStateError(ERROR_CODE_NOT_AVAILABLE_IN_REGION, any()) }
    }
}
```

## Related Documentation

- [`docs/architecture/voice-command-error-handling.md`](../architecture/voice-command-error-handling.md) - Voice command error handling architecture
- [`docs/features/android-auto.md`](../features/android-auto.md) - Android Auto feature documentation
- [`docs/features/playback.md`](../features/playback.md) - Playback feature documentation
- [`AGENT.md`](../../AGENT.md) - Testing approach and patterns

---

*Last Updated: 2026-02-01*
