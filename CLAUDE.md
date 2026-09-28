# CLAUDE.md

Guidance for working in this repo. Read before making changes.

## What this is

**BjornPlayer** — a local, offline, ad-free music player for Android. No internet
permission, no accounts. Scans the device's `MediaStore` and plays audio files.
Single-module Gradle project, Kotlin, Views + ViewBinding (no Compose).

## Build & run

- Windows host, PowerShell shell. Use `.\gradlew.bat` (a `gradlew` wrapper also exists).
- Build debug APK: `.\gradlew.bat assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Build release APK: `.\gradlew.bat assembleRelease`
- Unit tests (plain JVM, `app/src/test`): `.\gradlew.bat testDebugUnitTest`. CI runs them on
  every push/PR. No instrumented tests; beyond that, "verifying" means building and
  installing on a device.
- CI: `.github/workflows/build.yml` builds the debug APK on push to `master` and on PRs,
  and creates a GitHub Release with the APK attached when a `v*` tag is pushed.

### SDK / toolchain
- `compileSdk` = 36, `targetSdk` = 34, `minSdk` = 26 (Android 8.0 Oreo). Raising targetSdk to
  35+ enforces edge-to-edge on Android 15+ — needs inset handling first.
- Java/Kotlin target = 17. AGP 8.13.0, Kotlin 2.2.21, Gradle 8.13 wrapper, Media3 1.11.1.
- App id / namespace: `com.bjorntech.player`. Version in `app/build.gradle`
  (`versionCode` / `versionName`).

## Architecture

Media3 (ExoPlayer) for playback running in a foreground `MediaSessionService`. The UI
talks to playback through a `MediaController`, **not** directly to the player.

```
MainActivity ──connects──> MediaController ──session──> PlaybackService (ExoPlayer)
     │                                                         (separate process boundary)
     ├─ PlayerViewModel (LiveData: songs, currentSong, search, sort, isLoading)
     └─ Fragments: Songs / Artists / Albums / Favourites / NowPlaying / Queue / SongOptions
```

Key files (`app/src/main/java/com/bjorntech/player/`):

- **MainActivity.kt** — hosts bottom nav + mini "now playing" bar. Owns the
  `MediaController` connection (built in `onStart`, released in `onStop`). `playSong()` and
  `addToQueue()` build `MediaItem`s and drive the controller. Holds `currentQueue` to map
  media items back to `Song`s.
- **PlaybackService.kt** — `MediaSessionService` that builds the `ExoPlayer` (audio focus +
  pause-on-noisy enabled) and the `MediaSession`. Minimal by design.
- **PlayerViewModel.kt** — single source of UI truth via LiveData. `loadMusic()` scans on a
  coroutine; `filteredSongs()` applies search + sort.
- **MusicScanner.kt** — queries `MediaStore.Audio`, builds `Song`s, dedupes by
  title+artist, and groups by artist/album.
- **Song.kt** — data class; `id` is the `MediaStore` id and is used as the `MediaItem`
  `mediaId`.
- **NowPlayingFragment.kt** — full-screen bottom sheet: seekbar, shuffle/repeat, speed,
  sleep timer, swipe-to-change-track, Palette-tinted background, song info.
- **FavouritesManager.kt** — favourites persisted as a `Set<String>` of ids in
  SharedPreferences.
- **SongAdapter / GroupAdapter / QueueAdapter** — RecyclerView adapters.
- **QueueShuffleOrder.kt** — custom `ShuffleOrder` installed on the service's ExoPlayer.
  Fresh playlists (`cloneAndSet`) put the tapped song first;
  inserted items land right after their timeline predecessor, which is how "Add to queue"
  plays next in FIFO order with shuffle on. Unit-tested in `app/src/test` (QueueShuffleOrderTest);
  run `.\gradlew.bat testDebugUnitTest` after touching the index maths.
- **SleepTimer.kt** — process-wide timer bound to the service's player (attach/detach in
  `PlaybackService`). Lives outside the UI so it survives the sheet closing and the
  Activity's controller being released on screen-off.
- **SongInfo.kt** — shared "Song Info" dialog; file reads on `Dispatchers.IO`.
- **UpdateManager.kt** — checks GitHub Releases and installs a newer signed APK.

### Things that bite you here (important invariants)

- **Across the MediaSession boundary, only `mediaItem.mediaId` survives** —
  `localConfiguration` (and its `uri`) is stripped during serialisation. To resolve a
  playing item back to a `Song`, always look it up by `mediaId` against `currentQueue` /
  the songs list. Never read `localConfiguration?.uri` from a controller callback. See
  `MainActivity.onMediaItemTransition` and `QueueFragment`.
- The `MediaController` is **async** and may be `null` before it connects. Auto-play is
  guarded by `maybeAutoPlay()`, which can be called from several places and no-ops until
  both the controller is connected and songs are loaded.
- Fragments use the `_binding` / `binding` nullable pattern; null it in `onDestroyView`.
  Several `Handler`-based runnables (progress, seek, sleep timer) must be removed in
  `onStop` / `onDestroyView` to avoid leaks.
- `playSong(song, queue, shuffle = true)`: shuffle is on for the library/favourites/auto-play
  and off for artist/album drill-downs (album order). The library is also shuffled on load.
- `currentQueue` is per-Activity-instance; resolve mediaIds with `resolveSong()` (falls back
  to the library) and re-sync with `syncCurrentSongFromController()` on reconnect.
- `pendingQueued` (MainActivity) tracks user-queued mediaIds that haven't started; it's
  consumed in `onMediaItemTransition`.
- The service runs in the app's main process, which is why `SleepTimer` can be a plain
  singleton.

## Conventions

- Match the surrounding Kotlin style: ViewBinding, LiveData observers in `onViewCreated`,
  fully-qualified names used sparingly for one-off Android types.
- No new runtime permissions without explicit discussion. `INTERNET` exists **only** for the
  in-app updater (GitHub Releases) — no other network use; music data never leaves the device.
- Keep `PlaybackService` thin; playback orchestration lives in `MainActivity`.
