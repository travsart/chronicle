# Chapter System

This document covers Chronicle's chapter system, including chapter data flow, detection algorithm, track-to-chapter mapping, and navigation implementation.

## Overview

Chronicle supports chapter navigation for audiobooks, with chapters sourced from:
1. **M4B files** - Chapters embedded in the audio file, retrieved from Plex API
2. **Multi-file audiobooks** - Each audio file treated as a chapter

Chapters enable:
- Displaying progress within logical book sections
- Skip to next/previous chapter navigation
- Seekbar scoped to current chapter
- Chapter title display in player and mini player

Chronicle also filters out Plex transition markers when they appear alongside real embedded chapters. These markers are usually very short, sub-second boundary entries that would otherwise show up as duplicate `0:00` chapters in the UI.

---

## Architecture

```mermaid
graph TB
    subgraph Plex Server
        PlexAPI[Plex API]
    end
    
    subgraph Data Layer
        PlexChapter[PlexChapter Model]
        Chapter[Chapter Entity]
        ChapterDB[(ChapterDatabase)]
        ChapterRepo[ChapterRepository]
        TrackRepo[TrackRepository]
    end
    
    subgraph State Management
        CPS[CurrentlyPlayingSingleton]
        TLSM[TrackListStateManager]
        ProgressUpdater
    end
    
    subgraph UI Layer
        CLA[ChapterListAdapter]
        CPVM[CurrentlyPlayingViewModel]
        ADVM[AudiobookDetailsViewModel]
    end
    
    PlexAPI -->|JSON| PlexChapter
    PlexChapter -->|toChapter| Chapter
    Chapter --> ChapterDB
    ChapterDB --> ChapterRepo
    ChapterRepo --> ADVM
    TrackRepo --> TLSM
    ProgressUpdater --> CPS
    CPS --> CPVM
    CPVM --> CLA
    ADVM --> CLA
```

---

## Key Components

| Component | Purpose |
|-----------|---------|
| [`Chapter`](../../app/src/main/java/local/oss/chronicle/data/model/Chapter.kt) | Entity storing chapter metadata with Room persistence |
| [`PlexChapter`](../../app/src/main/java/local/oss/chronicle/data/sources/plex/model/PlexChapter.kt) | Plex API chapter model |
| [`ChapterDatabase`](../../app/src/main/java/local/oss/chronicle/data/local/ChapterDatabase.kt) | Room database for chapter persistence |
| [`ChapterRepository`](../../app/src/main/java/local/oss/chronicle/data/local/ChapterRepository.kt) | Loads and persists chapter data |
| [`ChapterListAdapter`](../../app/src/main/java/local/oss/chronicle/features/bookdetails/ChapterListAdapter.kt) | RecyclerView adapter for chapter list UI |
| [`TrackListStateManager`](../../app/src/main/java/local/oss/chronicle/features/player/TrackListStateManager.kt) | Manages track queue and position state |
| [`CurrentlyPlayingSingleton`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingSingleton.kt) | Single source of truth for current playback state |
| [`ProgressUpdater`](../../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt) | Polls player position and updates chapter state |

---

## Data Model

### Chapter Entity

The [`Chapter`](../../app/src/main/java/local/oss/chronicle/data/model/Chapter.kt:10) entity stores chapter metadata:

```kotlin
@Entity
data class Chapter(
    val title: String,
    @PrimaryKey(autoGenerate = true) val uid: Long = 0L,  // Auto-generated primary key
    val id: Long,                                         // Plex rating key (non-unique)
    val index: Long,
    val discNumber: Int,
    val startTimeOffset: Long,  // Milliseconds from track start
    val endTimeOffset: Long,    // Milliseconds from track start
    val downloaded: Boolean,
    val trackId: Long,          // Parent track ID
    val bookId: String,         // Parent audiobook ID
)
```

**Key Properties**:
- `uid` - Auto-generated primary key (unique per chapter row)
- `id` - Plex rating key (may be shared between multiple chapters)
- `startTimeOffset` / `endTimeOffset` - Position offsets relative to the **containing track**, not the book
- `trackId` - Links chapter to its containing audio file
- `bookId` - Parent audiobook ID for filtering and association
- `discNumber` - Used for multi-disc audiobooks with section headers

**Primary Key Change (ChapterDatabase v1→v2)**: Changed from `@PrimaryKey id` to `@PrimaryKey(autoGenerate = true) uid` to prevent overwrites when multiple chapters share the same track rating key. This fixes the bug where only the last chapter was displayed for multi-chapter audiobooks.

### Chapter Detection

The [`getChapterAt()`](../../app/src/main/java/local/oss/chronicle/data/model/Chapter.kt:51) extension function finds the chapter at a given position:

```kotlin
fun List<Chapter>.getChapterAt(trackId: Long, timeStamp: Long): Chapter {
    for (chapter in this) {
        if (chapter.trackId == trackId && 
            timeStamp in chapter.startTimeOffset until chapter.endTimeOffset) {
            return chapter
        }
    }
    return EMPTY_CHAPTER
}
```

The function matches a chapter when:
1. The `trackId` matches the current playing track
2. The `timeStamp` falls within `[startTimeOffset, endTimeOffset)`

---

## Data Flow

### Chapter Loading

```mermaid
sequenceDiagram
    participant UI as AudiobookDetailsFragment
    participant VM as AudiobookDetailsViewModel
    participant BookRepo as BookRepository
    participant ChapterRepo as ChapterRepository
    participant Resolver as ServerConnectionResolver
    participant Plex as PlexMediaService
    participant DB as ChapterDatabase
    
    UI->>VM: View audiobook
    VM->>BookRepo: fetchBookAsync(bookId, libraryId)
    BookRepo->>ChapterRepo: loadChapterData(bookId, libraryId)
    ChapterRepo->>Resolver: resolveConnection(libraryId)
    Resolver-->>ChapterRepo: ServerConnection
    ChapterRepo->>Plex: retrieveAlbum (library-specific)
    Plex-->>ChapterRepo: PlexChapter list
    ChapterRepo->>ChapterRepo: PlexChapter.toChapter(chapter, bookId)
    ChapterRepo->>DB: insertChapters
    DB-->>VM: chapters LiveData update
    VM-->>UI: Display chapter list
```

**Implementation**: [`ChapterRepository.loadChapterData()`](../../app/src/main/java/local/oss/chronicle/data/local/ChapterRepository.kt:43)

**Key Changes**:
- Chapter loading now library-aware via `ServerConnectionResolver` for multi-server setups
- `loadChapterData()` accepts `bookId` parameter to associate chapters with their audiobook
- `PlexChapter.toChapter()` conversion now accepts `bookId` parameter
- Chapters are correctly associated with their parent audiobook in the database
- Sub-second transition markers from Plex are skipped when the same track also contains a meaningful chapter, preventing duplicate `0:00` rows in chapter lists

### Fallback Behavior

When a track has no embedded chapters:
- A chapter is synthesized from the [`MediaItemTrack`](../../app/src/main/java/local/oss/chronicle/data/model/MediaItemTrack.kt) using [`track.asChapter()`](../../app/src/main/java/local/oss/chronicle/data/local/ChapterRepository.kt:66)
- The track title becomes the chapter title
- Chapter spans the entire track duration

### Plex Transition Markers

Some Plex chapter responses include two markers for the same track:
- A meaningful chapter entry that spans the real content
- A second boundary marker of roughly 40-50 ms at the chapter end

Those boundary markers are not useful for chapter navigation. If left unfiltered, they appear as duplicate entries with a displayed duration of `0:00`. Chronicle removes them with [`filterTransitionMarkers()`](../../app/src/main/java/local/oss/chronicle/data/model/Chapter.kt) whenever that track already has a real chapter, while still preserving genuinely short standalone chapters when no better chapter data exists.

---

## Chapter Detection During Playback

### Single Source of Truth Architecture

After the flip-flop fix, chapter detection follows a strict single-source-of-truth pattern:

```mermaid
graph LR
    subgraph Player
        ExoPlayer[ExoPlayer]
    end
    
    subgraph ProgressUpdater
        Poll[Poll every 1s]
        Detect[Detect chapter]
    end
    
    subgraph Singleton
        CPS[CurrentlyPlayingSingleton]
    end
    
    subgraph UI
        CPF[CurrentlyPlayingFragment]
        MP[Mini Player]
    end
    
    ExoPlayer -->|getCurrentPosition| Poll
    Poll --> Detect
    Detect -->|update| CPS
    CPS -->|StateFlow| CPF
    CPS -->|StateFlow| MP
```

### Detection Algorithm

[`ProgressUpdater`](../../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt) is the **sole component** that updates chapter state during playback:

1. Every ~1 second, polls `MediaPlayer.getCurrentPosition()`
2. Calculates absolute book position using track offsets
3. Determines current chapter using [`getChapterAt()`](../../app/src/main/java/local/oss/chronicle/data/model/Chapter.kt:51)
4. Updates [`CurrentlyPlayingSingleton`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingSingleton.kt) with new state
5. [`CurrentlyPlayingSingleton`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingSingleton.kt) deduplicates identical updates to prevent UI flicker

Before playback state and chapter lists consume embedded chapters, Chronicle filters transition markers so chapter selection is based on meaningful content chapters rather than the tiny boundary markers returned by Plex.

### Deduplication Guard

To prevent flip-flopping, [`CurrentlyPlayingSingleton.update()`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingSingleton.kt) includes a deduplication check:

```kotlin
fun update(/* parameters */) {
    // Skip identical updates to prevent flip-flopping
    if (/* values are identical to current state */) {
        Log.d(TAG, "Skipping identical update")
        return
    }
    // Proceed with state update and emissions
}
```

---

## Track-to-Chapter Mapping

### Relationship Model

```mermaid
erDiagram
    AUDIOBOOK ||--o{ TRACK : contains
    TRACK ||--o{ CHAPTER : contains
    
    AUDIOBOOK {
        int id PK
        string title
        long progress
    }
    
    TRACK {
        int id PK
        int bookId FK
        long duration
        long progress
        int discNumber
    }
    
    CHAPTER {
        long id PK
        long trackId FK
        long bookId FK
        long startTimeOffset
        long endTimeOffset
        int discNumber
        long index
    }
```

### Position Types

Chronicle distinguishes between two position types:

| Type | Description | Example |
|------|-------------|---------|
| **Track-relative** | Milliseconds from start of current track | Chapter navigation within a track |
| **Book-absolute** | Milliseconds from start of first track | Overall progress, seekbar position |

**Critical**: The [`EXTRA_ABSOLUTE_TRACK_POSITION`](../../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt) extras key provides the authoritative absolute position, avoiding manual calculation errors.

### Conversion Logic

[`TrackListStateManager`](../../app/src/main/java/local/oss/chronicle/features/player/TrackListStateManager.kt) handles position conversions:

```kotlin
val currentBookPosition: Long
    get() = trackList.getTrackStartTime(currentTrack) + currentTrackProgress
```

Where:
- `getTrackStartTime()` sums durations of all tracks before the current one
- `currentTrackProgress` is the offset within the current track

---

## Chapter Navigation

### Skip to Next/Previous Chapter

The [`CurrentlyPlayingViewModel`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingViewModel.kt) implements chapter navigation:

```mermaid
stateDiagram-v2
    [*] --> Playing
    Playing --> CheckIndex: Skip to Next/Previous
    CheckIndex --> GetNextChapter: index < chapters.size
    CheckIndex --> ShowToast: index >= chapters.size
    GetNextChapter --> SeekToChapter: Get chapter.startTimeOffset
    SeekToChapter --> Playing: MediaSession.seekTo
    ShowToast --> Playing: Already at boundary
```

**Implementation**:
- [`skipToNext()`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingViewModel.kt:432)
- [`skipToPrevious()`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingViewModel.kt:436)

### Jump to Chapter from List

When the user taps a chapter in the book details view:

1. [`ChapterListAdapter`](../../app/src/main/java/local/oss/chronicle/features/bookdetails/ChapterListAdapter.kt) invokes [`TrackClickListener.onClick(chapter)`](../../app/src/main/java/local/oss/chronicle/features/bookdetails/ChapterListAdapter.kt:229)
2. [`AudiobookDetailsViewModel.jumpToChapter()`](../../app/src/main/java/local/oss/chronicle/features/bookdetails/AudiobookDetailsViewModel.kt) is called with `chapter.startTimeOffset` and `chapter.trackId`
3. MediaSession transport controls seek to the chapter position

---

## UI Display

### Chapter List in Book Details

[`ChapterListAdapter`](../../app/src/main/java/local/oss/chronicle/features/bookdetails/ChapterListAdapter.kt) displays chapters with:
- Chapter title and duration
- Active chapter highlighting
- Disc section headers for multi-disc books

```mermaid
graph TD
    subgraph ChapterListAdapter
        SC[submitChapters]
        UCC[updateCurrentChapter]
    end
    
    subgraph ViewHolders
        CVH[ChapterViewHolder]
        SVH[SectionHeaderViewHolder]
    end
    
    subgraph Binding
        LIAB[list_item_audiobook_track.xml]
        LIDS[list_item_disc_number_section_heading.xml]
    end
    
    SC --> CVH
    SC --> SVH
    UCC --> SC
    CVH --> LIAB
    SVH --> LIDS
```

### Active Chapter Tracking

The adapter tracks the active chapter using a Triple:

```kotlin
private var activeChapter = Triple(trackId, discNumber, chapterIndex)

fun isActive(chapter: Chapter) =
    chapter.trackId == activeChapter.first &&
    chapter.discNumber == activeChapter.second &&
    chapter.index == activeChapter.third
```

Updates come from observing [`AudiobookDetailsViewModel.activeChapter`](../../app/src/main/java/local/oss/chronicle/features/bookdetails/AudiobookDetailsViewModel.kt) LiveData.

---

## Historical Fixes: Chapter Flip-Flop Bug

### Problem

Chapters would rapidly flip-flop between values during playback, causing UI flicker and incorrect chapter display.

### Root Causes

1. **Multiple update sources** - Several components updated [`CurrentlyPlayingSingleton`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingSingleton.kt) with conflicting values
2. **Stale database reads** - Components reading from the database got values before [`ProgressUpdater`](../../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt) had written
3. **Position confusion** - Mixing chapter-relative and book-absolute positions

### Solution

1. **Single update source** - Only [`ProgressUpdater`](../../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt) updates playback state
2. **Deduplication guard** - [`CurrentlyPlayingSingleton`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingSingleton.kt) skips identical consecutive updates
3. **Authoritative position** - Use [`EXTRA_ABSOLUTE_TRACK_POSITION`](../../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt) from extras bundle, never calculate manually
4. **Removed conflicting updates** - Eliminated `currentlyPlaying.update()` from [`OnMediaChangedCallback.onMetadataChanged()`](../../app/src/main/java/local/oss/chronicle/features/player/OnMediaChangedCallback.kt)

For detailed fix documentation, see [`plans/CHAPTER_FLIPFLOP_FIX.md`](../../plans/CHAPTER_FLIPFLOP_FIX.md).

---

## Historical Fixes: Duplicate Chapters Bug

### Problem

Chapters appeared duplicated 2-4 times in the audiobook details screen. Each library re-sync accumulated additional duplicate chapter rows in the database.

**Root Cause**: The primary key change from [`ChapterDatabase`](../../app/src/main/java/local/oss/chronicle/data/local/ChapterDatabase.kt) v1→v2 migration (from `id` to auto-generated `uid`) prevented `OnConflictStrategy.REPLACE` from detecting duplicates:

```kotlin
// v1: Primary key was id (Plex rating key)
@PrimaryKey val id: Long

// v2: Primary key became auto-generated uid
@PrimaryKey(autoGenerate = true) val uid: Long = 0L
val id: Long  // No longer primary key
```

With auto-generated `uid` as the primary key, each chapter insert creates a new row rather than replacing the existing one, because `uid` is always unique. The `OnConflictStrategy.REPLACE` had no way to detect that a chapter with the same `id`, `trackId`, and `index` already existed.

### Solution

Implemented a **delete-before-insert** pattern in [`ChapterRepository.loadChapterData()`](../../app/src/main/java/local/oss/chronicle/data/local/ChapterRepository.kt):

```kotlin
suspend fun loadChapterData(bookId: String, libraryId: String) {
    // 1. Delete all existing chapters for this book
    chapterDao.deleteByBookId(bookId)
    
    // 2. Insert fresh chapters from Plex API
    chapterDao.insertAll(chapters)
}
```

This ensures:
- No duplicates accumulate on re-sync
- Fresh chapter data is loaded on each sync
- Chapter count remains accurate regardless of sync frequency

**Alternative Considered**: Using a composite unique index on `(bookId, trackId, index)` was rejected because:
- It would cause constraint violations on insert rather than silent replacement
- Delete-before-insert is clearer and more maintainable
- Chapters are transient data that can be safely refetched from Plex

### Test Coverage

[`ChapterRepositoryTest`](../../app/src/test/java/local/oss/chronicle/data/local/ChapterRepositoryTest.kt) verifies:
- No duplicates after multiple re-syncs
- Correct delete-before-insert ordering
- Chapter count matches expected values

---

## Critical Implementation Constraints

1. **Single Update Source**: Only [`ProgressUpdater`](../../app/src/main/java/local/oss/chronicle/features/player/ProgressUpdater.kt) should update [`CurrentlyPlayingSingleton`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingSingleton.kt) during playback

2. **Use Absolute Positions**: Always use `EXTRA_ABSOLUTE_TRACK_POSITION` from extras bundle - never manually calculate from chapter offsets

3. **Database is Async**: Never assume database reads reflect the latest state - use in-memory state from [`CurrentlyPlayingSingleton`](../../app/src/main/java/local/oss/chronicle/features/currentlyplaying/CurrentlyPlayingSingleton.kt) when available

4. **Chapter State is Derived**: The current chapter is calculated from position at runtime using [`getChapterAt()`](../../app/src/main/java/local/oss/chronicle/data/model/Chapter.kt:51), not stored separately

5. **Track-Relative Offsets**: Chapter `startTimeOffset` and `endTimeOffset` are relative to the containing track, not the book

---

## Related Documentation

- [Media Playback](playback.md) - Player architecture and controls
- [Data Layer](../DATA_LAYER.md) - Database patterns
- [Track Info API](../example-query-responses/request_track_info.md) - Track metadata examples
- [Album Info API](../example-query-responses/request-album-info.md) - Audiobook metadata examples
- [Flip-Flop Fix Plan](../../plans/CHAPTER_FLIPFLOP_FIX.md) - Detailed fix documentation

---

**Last Updated**: 2026-01-23
