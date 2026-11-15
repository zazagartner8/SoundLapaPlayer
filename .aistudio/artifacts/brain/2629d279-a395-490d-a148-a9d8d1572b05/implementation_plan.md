# SoundLap v3.3 — UX, Visual Contrast, Player & Import Polish Plan

Comprehensive polish pass fixing dark-mode text contrast across all screens, blending the top video banner seamlessly into the background, redesigning the Now Playing screen with Spotify-style vertical scroll and uncramped controls, upgrading video clip framing and background detection, repairing cloud sync counters, accelerating playlist imports, and adding iOS-style waving glossy country flags.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions were confirmed during interactive clarification and govern this implementation:

- **Confirmed Decision 1 (Dark Theme Text Contrast)**: Audit and fix black-on-black text across **all dark screens, cards, dialogs, and overlays** so white/on-surface content color is guaranteed everywhere on dark surfaces.
- **Confirmed Decision 2 (Video Clip Framing)**: **Crop video to fill container (`RESIZE_MODE_ZOOM`)** with an opaque stage underneath while playing a clip so album artwork never peeks through along the edges.
- **Confirmed Decision 3 (Cover / Video Toggle Visibility)**: **Quietly probe official clip availability in the background** when a track loads in the player, and display the `Cover | Video` switcher only if an official clip exists.
- **Confirmed Decision 4 (Country Flag Style)**: Render **iOS-style waving ribbon flags with soft glossy sheen** in the language selector.

---

## 1. Overview & Core Concept

- **What It Delivers**: Eliminates dark-on-dark readability defects, gives the header video banner a seamless atmospheric dissolve into the app background, makes the fullscreen player spacious and scrollable like Spotify while preserving smooth swipe-down dismissal, ensures music videos fill their container edge-to-edge and only show the video switcher when a clip actually exists, keeps cloud sync track counts accurate in Settings, dramatically speeds up external playlist imports, and refines import field copy placement and language flags.
- **Key Value**: Every primary surface—Home, Wave, Search, Library, Now Playing, and Settings—gains cleaner hierarchy, higher contrast, and faster feedback with zero visual glitches.

---

## 2. User Experience & Visual Design

### Visual Contrast & Atmosphere
- **Guaranteed Light-on-Dark Content Color**: The root app container and transparent glass cards establish `onBackground` / `onSurface` as the active `LocalContentColor`, preventing Material 3's default black fallback on dark surfaces, cards, bottom sheets, dialogs, and empty states.
- **Seamless Top Banner Dissolve**: The header video banner retains crisp video detail near the top while its lower vertical gradient ramps smoothly to `100%` `MaterialTheme.colorScheme.background`, eliminating any sharp horizontal seam or hard rounded cutoff between the header video and screen content.

### Spotify-Style Now Playing Flow & Clip Presentation
```
┌──────────────────────────────────────────────┐
│  ═══   Swipe-down Handle / Top Bar / [⋮]     │
│      (Cover | Video pill shown iff found)    │
├──────────────────────────────────────────────┤
│   ┌──────────────────────────────────────┐   │
│   │  Cover Art / Zoom-Cropped Clip       │   │
│   │  (Opaque backdrop when video plays)  │   │
│   └──────────────────────────────────────┘   │
│   Track Title                     [+]  [♡]   │
│   Artist Name  ›                             │
│   ──────●────────────────────  01:12 / 03:24 │
│   [Shuffle]   [Prev]  (Play)  [Next] [Repeat]│
│   [ Next in Queue Pill ]            [Share]  │
├ ─ ─ ─ ─ ─ ─ ─ ─ Scroll Down ─ ─ ─ ─ ─ ─ ─ ─ ─┤
│   ┌──────────────────────────────────────┐   │
│   │  Lyrics Preview Card        [Expand] │   │
│   │  Active & upcoming synced lines...   │   │
│   └──────────────────────────────────────┘   │
└──────────────────────────────────────────────┘
```
- **Uncramped First Viewport**: Generous spacing separates cover/clip media, track title/artist, progress bar, Spotify-style transport controls, and Queue/Share dock.
- **Lyrics Below the Fold**: The expandable synced lyrics card sits cleanly below the initial controls area so the main player stays uncluttered yet scrolls smoothly down to preview or expand lyrics fullscreen.
- **Responsive Swipe-Down Dismiss**: Uses nested scroll coordination plus top-handle/header dragging so swiping down when scrolled to the top (`scrollOffset == 0`) smoothly drags and collapses the player sheet.

### Settings: Cloud Sync Counter, Playlist Import & iOS-Style Flags
- **Live Cloud Sync Counter**: Settings displays the real, continuously updated count of user library tracks (liked + uploaded + tracks in playlists) and playlists rather than staying stale or at `0`.
- **Reordered Import Card**:
  - Input field label becomes **«Ссылка плейлиста»** (*Playlist link* / *Link playlist*).
  - The fine-print tip (*«Имейте в виду, что практичнее и точнее будет вставить ссылку вместо названия»*) sits directly beneath that input field instead of at the bottom of the card.
- **iOS-Style Waving Glossy Flags**: Custom vector wave-curved flags with soft specular top-left highlight and subtle fabric edge shading for **RU**, **EN**, and **IT**.

---

## 3. Key Product Decisions & Trade-Offs

- **Root & Glass Surface `contentColor` Propagation**
  - *Chosen Approach*: Wrap root app content in `Surface(color = background, contentColor = onBackground)` and enforce `contentColor = MaterialTheme.colorScheme.onSurface` inside `LiquidGlassCard`, bottom sheets, and dark overlays, alongside explicit screen-by-screen dark contrast verification.
  - *Why*: Material 3 `Surface(color = Color.Transparent)` falls back to black `LocalContentColor` if no parent sets it, causing any unstyled `Text` or `Icon` on dark cards to disappear.
- **Crop-to-Fill Video + Silent Background Clip Probe**
  - *Chosen Approach*: Inflate clip view with `RESIZE_MODE_ZOOM`, hide/cover the underlying artwork inside an opaque stage once video playback begins, and start a cancellable low-priority background lookup whenever a track becomes active in the player to gate visibility of the `Cover | Video` switcher.
  - *Why*: Avoids letterboxing cover leaks on aspect-ratio mismatches while preventing users from tapping "Clip" on tracks that have no official video.
  - *Trade-Off*: Background clip checks use a cached stream lookup without fetching the heavy video stream until toggled on.
- **Parallel + Smart-Lazy Playlist Import**
  - *Chosen Approach*: Match tracks against local database first, raise network lookup concurrency (`Semaphore(12)`), tighten per-track search timeouts with fast YouTube/SoundCloud racing (skipping redundant cover artwork HTTP round-trips when playlist metadata already provides cover URLs), and materialize remaining tail tracks on oversized imports with instant `resolve-full` URIs.
  - *Why*: Cuts 50–100 track Spotify/Deezer playlist import times from minutes down to a few seconds while maintaining exact artist/title/cover fidelity.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

```
┌───────────────────────────┐     ┌──────────────────────────────────┐
│ Root Theme & Shell        │────▶│ Background Clip Probe (cached)   │
│ Surface + onBackground    │     │ Emits stream != null -> show pill│
└─────────────┬─────────────┘     └──────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────────────────────────────────┐
│ NowPlayingSheet (NestedScroll + Top-Drag Collapse)                 │
│  ├─ First Viewport: Uncramped Cover/Zoomed Clip + Controls + Dock  │
│  └─ Scroll Fold: Lyrics Preview Card ──▶ Fullscreen LRC Overlay    │
└────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌───────────────────────────┐     ┌──────────────────────────────────┐
│ Reactive Cloud Sync State │     │ Fast Playlist Importer           │
│ Live track/playlist count │     │ Local hit + 12x race + lazy tail │
└───────────────────────────┘     └──────────────────────────────────┘
```

- **State Updates**:
  - `CloudSyncUiState`: Track and playlist totals reactively track the personal library (liked tracks, user uploads, and unique playlist tracks) whenever local mutations or cloud sync finish.
  - `MusicVideoUiState`: Adds silent availability state (`trackId`, `isCheckingAvailability`, `stream`, `isVideoModeEnabled`) so the player switcher only renders when a clip exists or is confirmed available.
- **Interactive Mapping**:
  - Swiping downward inside `NowPlayingSheet` triggers nested-scroll pre-scroll capture when vertical scroll offset is at `0`, feeding `dragOffsetY` smoothly toward collapse threshold.
  - Playlist import parses source metadata, batches local/online track matching concurrently, persists playlist + cross-references, and immediately refreshes cloud metrics/counters.
