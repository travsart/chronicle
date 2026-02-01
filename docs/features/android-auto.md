# Android Auto Support

This document covers Chronicle's Android Auto integration for in-car audiobook playback.

## Overview

Chronicle supports Android Auto, allowing users to browse and play audiobooks directly from their car's infotainment system.

---

## Media Browser Structure

```
Root
├── Recently Listened
├── Offline (Downloaded)
├── Recently Added
└── Library (All Books)
```

The media browser hierarchy is designed for easy navigation while driving, with the most commonly accessed content at the top level.

---

## Features

| Feature | Description |
|---------|-------------|
| **Voice search** | Search for audiobooks by title or author |
| **Playback controls** | Play, pause, skip forward/back |
| **Book artwork** | Cover images displayed on screen |
| **Progress indicators** | Shows playback progress for each book |

---

## Implementation

### Key Methods

| Method | Purpose |
|--------|---------|
| [`MediaPlayerService.onGetRoot()`](../../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt) | Returns root of browsable content |
| [`MediaPlayerService.onLoadChildren()`](../../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt) | Loads content for a browsable node |
| [`MediaPlayerService.onSearch()`](../../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt) | Handles voice search queries |
| [`AudiobookMediaSessionCallback`](../../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) | Handles playback commands with error handling |

### Client Validation

Chronicle validates that the connecting client is an authorized Android Auto client:

- [`PackageValidator`](../../app/src/main/java/local/oss/chronicle/util/PackageValidator.kt) - Validates Auto client signatures

---

## Configuration

### XML Configuration Files

| File | Purpose |
|------|---------|
| [`auto_allowed_callers.xml`](../../app/src/main/res/xml/auto_allowed_callers.xml) | List of allowed Android Auto client packages |
| [`automotive_app_desc.xml`](../../app/src/main/res/xml/automotive_app_desc.xml) | Declares Auto capabilities to the system |

### Enabling Android Auto

Users must enable Android Auto support in Chronicle's settings:

1. Go to Settings
2. Toggle "Allow Android Auto" on
3. Connect to Android Auto in vehicle

---

## MediaBrowserService Flow

```mermaid
sequenceDiagram
    participant Auto as Android Auto
    participant MPS as MediaPlayerService
    participant Repo as BookRepository
    
    Auto->>MPS: connect
    MPS->>MPS: validate client
    MPS-->>Auto: connection accepted
    
    Auto->>MPS: onGetRoot
    MPS-->>Auto: root node
    
    Auto->>MPS: onLoadChildren root
    MPS-->>Auto: Recently Listened, Offline, Recently Added, Library
    
    Auto->>MPS: onLoadChildren Library
    MPS->>Repo: getAllBooks
    Repo-->>MPS: books list
    MPS-->>Auto: browsable book items
    
    Auto->>MPS: playFromMediaId bookId
    MPS->>MPS: start playback
```

---

## Error Handling

### Logging and Debugging

Android Auto operations use `[AndroidAuto]` prefixed log tags for easier debugging:

```kotlin
Timber.d("[AndroidAuto] Loading children for parentId: $parentId")
Timber.e("[AndroidAuto] Error loading book: ${error.message}")
```

### Defensive Null Checks

All browse tree operations include null safety:

```kotlin
// Safe book lookup with fallback
val books = bookRepository.getAllBooks()
    .filterNotNull()
    .map { it.toMediaItem() }
```

### Error Recovery

When media loading fails:

1. Log error with `[AndroidAuto]` tag
2. Return empty browser result (not crash)
3. Display user-friendly message if possible

---

## MediaSession State Synchronization

Android Auto receives playback state through [`PlaybackStateController`](../../app/src/main/java/local/oss/chronicle/features/player/PlaybackStateController.kt):

```mermaid
graph LR
    subgraph State Updates
        PSC[PlaybackStateController]
        SF[StateFlow]
    end
    
    subgraph MediaSession
        MS[MediaSession]
        PSB[PlaybackStateCompat.Builder]
    end
    
    subgraph Android Auto
        AA[Android Auto UI]
    end
    
    PSC --> SF
    SF --> MS
    MS --> PSB
    PSB --> AA
```

### State Synchronization

| State | Source | Update Trigger |
|-------|--------|----------------|
| Playing/Paused | ExoPlayer | Player state change |
| Position | PlaybackStateController | Position updates (debounced) |
| Track metadata | TrackListStateManager | Track change |
| Error state | ChronicleError | Playback error |

## Safety Considerations

- UI is simplified for driver safety
- Large touch targets for easy tapping
- Voice control support reduces distraction
- No text input while driving

---

## Voice Command Handling

Chronicle fully supports voice commands from Android Auto and Google Assistant, meeting Google Play Store requirements that **all voice commands must either start playback or show a visible error message** (silent failures are not allowed).

### Supported Voice Commands

Chronicle handles the following MediaSession callbacks for voice commands:

| Callback | Triggered By | Example |
|----------|--------------|---------|
| [`onPlay()`](../../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) | Generic play command | "Hey Google, play on Chronicle" |
| [`onPlayFromSearch()`](../../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) | Search query | "Hey Google, play [book name] on Chronicle" |
| [`onPlayFromMediaId()`](../../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) | Direct media selection | User taps book in Android Auto UI |

**Critical Requirement**: Every command **must** result in either:
- ✅ Successful playback starts
- ✅ User sees/hears an error message

Silent failures result in app rejection from Google Play.

---

### Error Response Strategy

Chronicle uses the [`IPlaybackErrorReporter`](../../app/src/main/java/local/oss/chronicle/features/player/IPlaybackErrorReporter.kt) interface to communicate errors from the MediaSession callback to the service:

```kotlin
interface IPlaybackErrorReporter {
    fun setPlaybackStateError(errorCode: Int, errorMessage: String)
    fun clearPlaybackError()
}
```

#### Implementation

[`MediaPlayerService`](../../app/src/main/java/local/oss/chronicle/features/player/MediaPlayerService.kt) implements this interface and sets `PlaybackStateCompat.STATE_ERROR`, which causes Android Auto to display the error message both visually on screen and via Google Assistant voice feedback.

#### Error Scenarios and Messages

| Scenario | Error Message | User Action Required |
|----------|---------------|---------------------|
| Not logged in | "Not logged in to Chronicle" | Open app and log in |
| No server chosen | "No server chosen. Please return to Chronicle and choose a server and library" | Complete Plex server setup |
| No user chosen | "No user chosen. Please return to Chronicle and finish logging in" | Complete user selection |
| No library chosen | "No library chosen. Please return to Chronicle and choose a library" | Select audiobook library |
| Search returns no results | "No audiobooks found for '[query]'" | Try different search terms |
| Empty library | "Your audiobook library is empty" | Add audiobooks to Plex |
| Connection timeout | "Connection timeout - please check your network" | Check network/Plex connection |
| Audiobook not available | "This audiobook is not available" | Book may have been removed |
| Playback failed | "Unable to play audiobook" | Check app/server state |

All error strings are defined in [`strings.xml`](../../app/src/main/res/values/strings.xml) with the `auto_access_error_` prefix.

---

### Authentication Checks

**All voice command callbacks check authentication state before proceeding.** This prevents silent failures when users aren't fully logged in.

The [`checkAuthenticationOrError()`](../../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) helper method validates:

1. User is logged in to Plex
2. Server has been chosen
3. User account selected (for managed users)
4. Audiobook library selected

If any check fails, an appropriate error message is shown immediately via `setPlaybackStateError()`.

**Example Flow:**
```mermaid
flowchart TD
    A[Voice Command] --> B{Check Login State}
    B -->|Not Logged In| C[Show Error:<br/>Not logged in to Chronicle]
    B -->|No Server| D[Show Error:<br/>No server chosen]
    B -->|No Library| E[Show Error:<br/>No library chosen]
    B -->|Fully Logged In| F[Execute Command]
    
    C --> G[User Sees/Hears Error]
    D --> G
    E --> G
    F --> H{Command Success?}
    H -->|Yes| I[Start Playback]
    H -->|No| J[Show Specific Error]
    J --> G
    
    style C fill:#ff6b6b
    style D fill:#ff6b6b
    style E fill:#ff6b6b
    style G fill:#ff6b6b
    style I fill:#51cf66
```

---

### Fallback Playback Behavior

Chronicle implements intelligent fallback behavior for generic vs. specific commands:

#### Empty/Generic Queries

**Voice command**: "Hey Google, play on Chronicle" (no specific book requested)

**Fallback strategy**:
1. Try to play most recently listened audiobook
2. If no recent history, play a random audiobook
3. If library is empty, show error: "Your audiobook library is empty"

#### Specific Search Queries

**Voice command**: "Hey Google, play [specific book name] on Chronicle"

**Fallback behavior (configurable)**:

When the **"Resume on failed voice search" setting is enabled** (default):
1. Try to play the most recently listened audiobook
2. If no recent history, play the first book in the library
3. If library is empty, show error: "Your audiobook library is empty"

When the **setting is disabled**:
- Show error with the search query: "No audiobooks found for '[query]'"

**Rationale**: Some users prefer to have something play (their recent book) rather than get an error when voice search finds nothing. Others prefer to know when their specific request failed. The setting provides flexibility for both preferences.

**See Also**: [`AudiobookMediaSessionCallback.handleSearchSuspend()`](../../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt) for implementation details.

#### Direct Media ID

When browsing Android Auto UI and tapping a book, there is **no fallback** - only that specific book should play or show an error.

---

### Timeout Handling

To prevent infinite waiting when network/server isn't available, Chronicle implements a **10-second timeout** for connection establishment.

**Constant**: `RESUME_TIMEOUT_MILLIS = 10_000L` in [`AudiobookMediaSessionCallback`](../../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt)

**When timeout occurs**:
- User sees error: "Connection timeout - please check your network"
- No infinite spinners or hanging state
- User can retry by issuing command again

This prevents the Google Play violation where apps hang indefinitely without feedback.

---

### Voice Search Fallback Setting

Chronicle provides a user-configurable setting to control behavior when voice search returns no results.

**Setting Details:**
- **Name**: "Resume on failed voice search"
- **Preference Key**: `key_voice_search_fallback_enabled`
- **Default**: Enabled (ON)
- **Location**: Settings → Android Auto section

**Behavior when enabled:**

When a user says "play something" or searches for a book that isn't found, instead of showing an error, Chronicle will:

1. **Try most recently played book** - Resume the audiobook you were last listening to
2. **Fallback to first in library** - If no playback history exists, play the first audiobook alphabetically
3. **Show error only if library empty** - Only displays an error if there are no audiobooks at all

**Behavior when disabled:**

Voice searches that return no results will show an error message:
- "No audiobooks found for '[query]'"

**Implementation Flow:**

```mermaid
flowchart TD
    A[Voice Search Command] --> B{Search finds<br/>results?}
    B -->|Yes| C[Play matching audiobook]
    B -->|No| D{Fallback setting<br/>enabled?}
    D -->|No| E[Show Error:<br/>No audiobooks found]
    D -->|Yes| F{Recent book<br/>exists?}
    F -->|Yes| G[Resume recent book]
    F -->|No| H{Library has<br/>books?}
    H -->|Yes| I[Play first book]
    H -->|No| J[Show Error:<br/>Library is empty]
    
    style C fill:#51cf66
    style G fill:#51cf66
    style I fill:#51cf66
    style E fill:#ff6b6b
    style J fill:#ff6b6b
```

**Use Cases:**

| User Type | Preference | Setting |
|-----------|-----------|---------|
| Wants to always resume listening with minimal friction | "Just play something when I ask" | Enabled ✓ |
| Wants explicit feedback when search fails | "Tell me when my request wasn't found" | Disabled ✗ |
| Uses generic commands like "play on Chronicle" | "Resume my recent book" | Enabled ✓ |
| Uses specific book titles in voice commands | "I want to know if that specific book wasn't found" | Disabled ✗ |

**Code Reference:**
- Implementation: [`AudiobookMediaSessionCallback.handleSearchSuspend()`](../../app/src/main/java/local/oss/chronicle/features/player/AudiobookMediaSessionCallback.kt)
- Setting storage: [`SharedPreferencesPrefsRepo.voiceSearchFallbackEnabled`](../../app/src/main/java/local/oss/chronicle/data/local/SharedPreferencesPrefsRepo.kt)
- Tests: [`VoiceSearchFallbackTest.kt`](../../app/src/test/java/local/oss/chronicle/features/player/VoiceSearchFallbackTest.kt)

---

### Manual Testing Checklist

Use the **Android Auto Desktop Head Unit (DHU)** simulator or a real vehicle to test:

#### Logged Out Scenarios
- [ ] "Hey Google, play on Chronicle" → Shows "Not logged in to Chronicle" error
- [ ] "Hey Google, play [book name] on Chronicle" → Shows "Not logged in to Chronicle" error
- [ ] Verify error is shown on Android Auto screen
- [ ] Verify error is spoken by Google Assistant

#### Logged In Scenarios (with fallback enabled)
- [ ] "Hey Google, play on Chronicle" → Plays last played or first audiobook
- [ ] "Hey Google, play [existing book] on Chronicle" → Plays matching audiobook
- [ ] "Hey Google, play [nonexistent book] on Chronicle" → Fallback: Plays most recent or first book
- [ ] Generic play with no history → Plays first book in library
- [ ] Generic play with recent book → Resumes recent book

#### Logged In Scenarios (with fallback disabled)
- [ ] "Hey Google, play on Chronicle" → Plays last played or first audiobook
- [ ] "Hey Google, play [nonexistent book] on Chronicle" → Shows "No audiobooks found for '[query]'" error
- [ ] Search with no results → Shows error (no fallback to recent/first)

#### Setup Incomplete Scenarios
- [ ] Logged in, no server chosen → Shows "No server chosen" error
- [ ] Logged in, no library chosen → Shows "No library chosen" error
- [ ] Each error message is clear and actionable

#### Edge Cases
- [ ] Empty library + generic play → Shows "library is empty" error
- [ ] Empty library + search (fallback enabled) → Shows "library is empty" error
- [ ] Empty library + search (fallback disabled) → Shows "library is empty" error
- [ ] Network disconnected → Shows timeout error after 10 seconds (not infinite wait)
- [ ] Invalid media ID from browse → Shows "audiobook not available" error
- [ ] Book with no tracks → Shows appropriate error
- [ ] Toggle fallback setting → Behavior changes appropriately on next search

#### Error Message Verification
- [ ] All error messages appear on Android Auto screen
- [ ] All errors are spoken by Google Assistant
- [ ] App doesn't crash on any error scenario
- [ ] Error state clears when playback succeeds
- [ ] No silent failures - every command produces visible feedback

#### Performance
- [ ] Commands respond within 2-3 seconds under normal conditions
- [ ] Timeout occurs at exactly 10 seconds (not sooner or later)
- [ ] No memory leaks when triggering multiple errors

---

## Related Documentation

- [Features Index](../FEATURES.md) - Overview of all features
- [Playback](playback.md) - Media playback architecture and state management
- [Settings](settings.md) - Enabling Android Auto
- [Architecture Patterns](../architecture/patterns.md) - MediaBrowserService pattern, PlaybackStateController
- [Voice Command Error Handling Architecture](../architecture/voice-command-error-handling.md) - Detailed design and implementation
- [Voice Command Testing Guide](../testing/android-auto-voice-command-testing.md) - Comprehensive testing guide for Android Auto and voice commands
