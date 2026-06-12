# 🎵 Bjorn Player

A clean, ad-free local music player for Android. No accounts. No internet. No nonsense.

[![Build APK](https://github.com/Xbjornsen/BjornPlayer/actions/workflows/build.yml/badge.svg)](https://github.com/Xbjornsen/BjornPlayer/actions/workflows/build.yml)
![Platform](https://img.shields.io/badge/platform-Android-green?logo=android)
![Min SDK](https://img.shields.io/badge/minSdk-26%20(Oreo)-blue)
![Language](https://img.shields.io/badge/language-Kotlin-purple?logo=kotlin)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## Features

- 🎧 Plays MP3, FLAC, AAC, OGG and most common audio formats
- 🗂️ Browse by **Songs**, **Artists**, **Albums**, **Favourites**
- 🔍 Search across your entire library
- ▶️ Background playback with lock screen & notification controls
- 🎨 Album art from embedded tags with colour-matched UI (Palette)
- 🔇 Auto-pauses on headphone unplug
- 📋 Queue management
- ⚙️ Built-in equalizer view
- 🌗 Follows your system light/dark theme
- 🔄 In-app updates straight from GitHub Releases — no app store needed
- 🔒 Your music never leaves the device (network is used only to check for updates)

---

## Screenshots

> _Coming soon_

---

## Tech Stack

| Layer | Library |
|---|---|
| Media playback | [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer) |
| Media session / notifications | Media3 MediaSession |
| Image loading | [Glide](https://github.com/bumptech/glide) |
| Colour extraction | [Palette KTX](https://developer.android.com/reference/kotlin/androidx/palette/graphics/Palette) |
| UI | Views + ViewBinding, Material 3 |
| Architecture | ViewModel + LiveData |

---

## Requirements

- Android **8.0 (Oreo)** or higher (minSdk 26)
- Android Studio **Hedgehog (2023.1.1)** or newer for building

---

## Building from Source

1. **Install Android Studio** → https://developer.android.com/studio
2. Open Android Studio → *Open an existing project* → select this folder
3. Wait for Gradle sync to finish (first run downloads dependencies)
4. Connect your Android phone via USB
5. Enable Developer Options:
   - *Settings → About Phone* → tap **Build Number** 7 times
   - *Settings → Developer Options* → enable **USB Debugging**
6. Hit the green ▶ **Run** button — the app installs directly on your phone

### Sideloading the APK

1. *Build → Build Bundle(s)/APK(s) → Build APK(s)*
2. APK will be at: `app/build/outputs/apk/debug/app-debug.apk`
3. Transfer to your phone and install (requires *Install unknown apps* permission)

---

## Permissions

| Permission | Reason |
|---|---|
| `READ_MEDIA_AUDIO` (Android 13+) | Scan music files |
| `READ_EXTERNAL_STORAGE` (Android 12 and below) | Scan music files |
| `FOREGROUND_SERVICE` | Keep playing with screen off |
| `WAKE_LOCK` | Prevent audio cutting out during playback |
| `INTERNET` | Check GitHub Releases for app updates and download the new APK |
| `REQUEST_INSTALL_PACKAGES` | Install a downloaded update |

**Network is used only for the update check** — no accounts, no analytics, and your
music never leaves the device.

---

## Project Structure

```
app/src/main/java/com/bjorntech/player/
├── MainActivity.kt          # Entry point, navigation host
├── PlayerViewModel.kt       # Shared playback state (ViewModel)
├── PlaybackService.kt       # Media3 background playback service
├── MusicScanner.kt          # Scans device MediaStore for audio
├── Song.kt                  # Data model
├── SongsFragment.kt         # All songs tab
├── ArtistsFragment.kt       # Artists tab
├── AlbumsFragment.kt        # Albums tab
├── FavouritesFragment.kt    # Favourites tab
├── NowPlayingFragment.kt    # Full-screen now playing
├── QueueFragment.kt         # Playback queue
├── SongOptionsFragment.kt   # Per-song context menu
├── SongAdapter.kt           # RecyclerView adapter
├── SongListFragment.kt      # Reusable song list
├── FavouritesManager.kt     # Persist favourite songs
└── EqualizerView.kt         # Custom equalizer visualiser
```

---

## License

MIT — do whatever you like with it.
