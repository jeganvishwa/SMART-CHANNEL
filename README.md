# FreeTV – Android TV IPTV player

FreeTV is an offline-first Android TV IPTV player. It has no backend: playlists (M3U/M3U8),
channels, groups, channel numbers, favourites and EPG are all stored on the device in Room/DataStore.

> FreeTV ships with **no channel lists**. It only plays streams the user is authorised to access, and it
> never bypasses DRM, authentication or geo-restrictions.

## Toolchain (verified)

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.2.1 (built-in Kotlin) |
| Gradle | 9.4.1 (wrapper) |
| Kotlin / KSP | 2.3.21 / 2.3.14 |
| JDK for Gradle | 17 or newer (Android Studio's bundled JBR 21 works) |
| compileSdk / targetSdk / minSdk | 37 / 36 / 23 |

All library versions are in [gradle/libs.versions.toml](gradle/libs.versions.toml).
Navigation (2.9.x), DataStore (1.2.x) and WorkManager (2.11.x) stay on the newest
releases that still support **API 23**, because their next minor versions require API 24.

## Project layout

```
app/src/main/java/com/example/freetv/
├── FreeTvApplication.kt      Hilt app, WorkManager + Coil configuration
├── MainActivity.kt           Single activity, splash screen, Compose host
├── data/                     database, dao, entity, repository, parser, network
├── domain/                   model, repository (interfaces), usecase
├── presentation/             Compose for TV screens, ViewModels, navigation, theme
├── player/                   ExoPlayerManager, PlaybackController, StreamFailoverManager, NumericChannelController
├── preferences/              DataStore settings
├── di/                       Hilt modules
└── utils/                    Shared helpers (URL validation / credential redaction)
```

## Build

### Android Studio
1. **File → Open** → select this folder.
2. **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** → choose the bundled *JetBrains Runtime 21*.
3. Let Gradle sync, choose a TV device or emulator, then press **Run**.

### Command line (Windows, Git Bash)
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
```
PowerShell:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

### Install on an Android TV
```bash
adb connect <tv-ip-address>:5555        # enable Developer options → Network/USB debugging on the TV
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
The debug build installs as `com.example.freetv.debug` beside a release build.

### Release APK
1. Create a keystore once:
   `keytool -genkeypair -v -keystore freetv-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias freetv`
2. Create `keystore.properties` in the project root (it is git-ignored):
   ```properties
   storeFile=freetv-release.jks
   storePassword=...
   keyAlias=freetv
   keyPassword=...
   ```
3. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (minified and resource-shrunk).
   Without `keystore.properties` the release APK is built unsigned.

## Development status

| Phase | Scope | Status |
|---|---|---|
| 1 | Project setup, Gradle, architecture, DI | ✅ Done |
| 2 | Room database, DAOs, repositories, DataStore | ⏳ |
| 3 | M3U parser, URL and file import | ⏳ |
| 4 | Channel detection, multiple playlist management | ⏳ |
| 5 | Custom groups, channel management | ⏳ |
| 6 | Channel numbers, remote numeric switching | ⏳ |
| 7 | Media3 ExoPlayer, full-screen player | ⏳ |
| 8 | Multiple stream URLs, automatic failover | ⏳ |
| 9 | Language memory, favourites, settings | ⏳ |
| 10 | Premium TV UI, navigation, focus | ⏳ |
| 11 | XMLTV EPG | ⏳ |
| 12 | Tests, device testing, optimisation, APK | ⏳ |
