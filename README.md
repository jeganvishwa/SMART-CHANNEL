# FreeTV – IPTV player for Google TV / Android TV

FreeTV plays **your own M3U / M3U8 playlists** on Google TV and Android TV. It has no backend server, account or subscription. Everything (playlists, channels, numbers, groups, favourites, TV guide) is stored on the TV.

> FreeTV ships with **no channels**. Only add playlists and streams you are allowed to watch. FreeTV never bypasses DRM, logins or geo-restrictions.

## Features

- **Multiple playlists**: add M3U/M3U8 by URL or import a file, as many as you like. Each one can be edited, refreshed, enabled/disabled or deleted, with download progress and clear error messages.
- **Automatic channel detection**: reads `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `tvg-language`, `#EXTGRP`, `#EXTVLCOPT` (user-agent/referrer), quoted values that contain commas, UTF-8 (Tamil, Hindi, Malayalam…), and skips broken entries.
- **Duplicate handling**: entries with the same `tvg-id` become one channel that has backup streams. A playlist URL added twice triggers a warning.
- **Groups**: Tamil, English, Hindi and Malayalam language groups plus News, Movies, Sports, Kids, Entertainment and Music are created and filled automatically. You can create unlimited custom groups, rename, reorder, hide or delete them, and add, remove or move channels. A channel can belong to several groups.
- **Channel numbers**: channels are numbered automatically on import. You can set any number by hand; duplicates are blocked and you're offered a swap. Channels can be moved up and down, or everything renumbered at once.
- **Remote control**: type `1`, `25`, `125`… to jump straight to that channel. Number format (digits) and input timeout are configurable. ▲▼ / CH+ CH− change channel, ◄ opens the channel list, ► or OK opens options, and LAST returns to the previous channel.
- **Language memory**: the last selected language tab (for example Tamil) is restored at every start. A default language can be set instead.
- **Multiple streams per channel**: add, edit, delete, reorder or test URLs and set the primary one. If a stream fails it is retried, then the next backup is tried automatically, with a retry limit so it never loops forever. You can also pick a source manually.
- **Player (Media3 ExoPlayer)**: HLS, DASH and progressive streams; a single player is reused for fast zapping; buffering profiles; a stall watchdog; offline detection; audio track, subtitle and aspect ratio selection; an info banner with logo, number, name and now/next programmes; favourite toggle; optional auto-play of the last watched channel.
- **Favourites**, an **XMLTV TV guide** (optional, `.xml` or `.xml.gz`, with time-zone correction), **background auto-refresh**, **database backup/restore** and **clear cache**.

## Remote control keys

| Key | Home screen | Player |
|---|---|---|
| 0–9 | Jump to channel number | Jump to channel number |
| OK | Play channel (hold: add to favourites) | Show info, press again for options |
| ▲ / ▼, CH+ / CH− | Move focus | Next / previous channel |
| ◄ | Move focus | Channel list |
| ► / MENU | Move focus | Options (favourite, source, audio, subtitles, aspect ratio) |
| LAST | – | Previous channel |
| BACK | Go back | Close panel, then exit player |

## Install on Google TV

The ready-to-install file is **`FreeTV-debug.apk`** (build it with the commands below; it ends up in `app/build/outputs/apk/debug/app-debug.apk`).

### Option A – from this PC with ADB (recommended)

1. On the TV: **Settings → System → About**, then press OK on **Android TV OS build** 7 times to unlock Developer options.
2. **Settings → System → Developer options**: turn on **USB debugging**. On Google TV with Android 14 TV or newer, also turn on **Wireless debugging**.
3. Find the TV's IP address under **Settings → Network & Internet → (your Wi-Fi)**.
4. On the PC (same Wi-Fi):

   ```bash
   # Android 14 TV and newer: pair first using the code shown under "Wireless debugging → Pair device"
   adb pair <tv-ip>:<pairing-port>
   adb connect <tv-ip>:<port shown on the Wireless debugging screen>
   # Older Google TV / Android TV:
   adb connect <tv-ip>:5555

   adb install -r FreeTV-debug.apk
   ```

5. Approve the "Allow USB debugging?" prompt on the TV. FreeTV then appears in your Apps row.

### Option B – without a PC

1. Upload `FreeTV-debug.apk` to Google Drive or another download link.
2. On the TV, install the **Downloader** app (by AFTVnews) from the Play Store.
3. **Settings → Apps → Security & restrictions → Unknown sources** → allow **Downloader**.
4. Open Downloader, enter the APK link and install it.

### First use

1. Open FreeTV, press OK on **Add playlist** and enter your M3U link.
2. Channels are detected, numbered and grouped by language automatically.
3. Pick a language tab (Tamil, English…), press OK on a channel, and you're watching. Type a number on the remote to jump to any channel.

## Build

Toolchain: AGP 9.2.1 (built-in Kotlin), Gradle 9.4.1, Kotlin 2.3.21, KSP 2.3.14, Hilt 2.60.1, Media3 1.11.1, Room 2.8.4, Compose BOM 2026.09.00, TV Material 1.1.0. compileSdk 37, targetSdk 36, minSdk 23. Navigation (2.9.x), DataStore (1.2.x) and WorkManager (2.11.x) are kept on the newest releases that still support API 23.

A JDK 17+ is required. Android Studio's bundled JBR 21 works:

```bash
# Git Bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 41 unit tests
```

```powershell
# PowerShell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

In Android Studio: **File → Open** this folder, set **Settings → Build Tools → Gradle → Gradle JDK** to the bundled JBR 21, then Run.

### Release APK

1. `keytool -genkeypair -v -keystore freetv-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias freetv`
2. Create `keystore.properties` in the project root (it is git-ignored):

   ```properties
   storeFile=freetv-release.jks
   storePassword=...
   keyAlias=freetv
   keyPassword=...
   ```

3. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (minified and signed).

## Architecture

```text
com.example.freetv
├── data/        database (Room), dao, entity, repository (impl + PlaylistSyncer), parser (M3U, XMLTV), network
├── domain/      model, repository interfaces, usecase
├── player/      ExoPlayerManager, PlaybackController, StreamFailoverManager, NumericChannelController
├── presentation/ home, player, playlist, channels, groups, favorites, epg, settings, navigation, components, theme
├── preferences/ DataStore settings (language memory, player and remote options)
├── worker/      background playlist / EPG refresh (WorkManager + Hilt)
├── di/          Hilt modules
└── utils/       URL validation and credential redaction for logs
```

How a refresh keeps your changes: each channel is identified by its playlist plus a stable key (the `tvg-id`, or the normalised name). On refresh, metadata is updated, while your numbers, favourites, group choices, renamed names/logos and streams you added are kept. A channel removed from the playlist is deleted only if you never customised it.

## Tests

`./gradlew testDebugUnitTest` runs 41 tests covering M3U parsing, duplicate merging, multiple-playlist import, refresh preservation, group creation and assignment, number assignment, duplicate-number prevention and swapping, backup-stream failover, remote numeric input, language persistence, XMLTV parsing and URL redaction.

Manual check on a TV: remote navigation on every screen, number keys in the player and on home, Home/Back during playback (playback stops and resumes), restarting the app (language and last channel restored), unplugging the network during playback (the app waits, then resumes), and a large playlist (10k+ channels).
