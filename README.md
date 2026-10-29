# SNESLive TV Search & Play

Android TV / Fire TV WebView application with a searchable 57-game featured catalog and in-app search of SNESLive's full collection. Selecting a game opens an app-owned, full-screen player with automatic emulator startup. The website's advertising page, banners, and video pre-roll are excluded. No ROMs or emulator binaries are bundled.

Designed for Fire TV Stick 4K Max (2nd Gen, AFTKRT), Fire OS 8 / Android 11 / API 30. This is a universal Java APK with no ABI-specific native libraries and no Google Play Services dependency. Hardware compatibility and gameplay need confirmation on a physical Fire TV. Build success, synthetic input tests, and a loaded web page do not establish working gameplay.

## Build

Use JDK 17, Android SDK platform 35, and build tools 35.0.0. The wrapper pins Gradle 8.10.2 and verifies its distribution checksum; Android Gradle Plugin is 8.7.3.

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Installable output: `app/build/outputs/apk/debug/app-debug.apk`. Version 1.3.0 (code 4), package `com.retrosearch.play`, min SDK 23, target SDK 35. The APK is debug-signed for sideload testing.

## Controls

In the catalog, D-pad / left stick moves focus; Center / Xbox A / PlayStation Cross selects. Search filters the local list immediately. Enter with one result opens that game; with multiple results it focuses the choices. Search all games brings online results and page controls into the app catalog.

| Input | SNES button |
| --- | --- |
| Xbox A / PlayStation Cross | B |
| Xbox B / PlayStation Circle | A |
| Xbox X / PlayStation Square | Y |
| Xbox Y / PlayStation Triangle | X |
| LB / L1; RB / R1 | L; R |
| Start / Options; View / Share | Start; Select |
| D-pad / left stick | Direction pad |
| Remote Center; Rewind; Fast Forward; Play/Pause | B; Y; A; Start |
| Remote extra bank: Center; Rewind; Fast Forward; Play/Pause | X; L; R; Select |

Menu opens the app toolbar. The Remote button switches banks. Back first opens the toolbar, then returns to Games. Home remains a system button. Select Start / Focus to resume after leaving the app. A gamepad is needed for full simultaneous input; the Fire remote has limited buttons.

Controller input uses Android's standard button positions, independent of the controller name. D-pad key and hat events and left-stick movement are handled, with a deadzone, duplicate-event merging, and release on focus loss, pause, and disconnect. The app sends SNES input IDs to the live EmulatorJS input API; browser gamepad input is gated while native controls are active to avoid duplicate presses. Leaving native game mode restores the emulator's original input handler.

## Test

`./gradlew :app:testDebugUnitTest :app:lintDebug` runs controller and ad-free player unit tests plus Android lint. Player tests verify metadata extraction, URL validation, advertising exclusion, and failure behavior.

The browser suite checks catalog search, TV focus, the actual game-only template, advertising request rejection by its content policy, download errors, Start activation, duplicate suppression, and release/resume against a synthetic input sink:

```sh
npm install
npx playwright install chromium
npm run test:web
```

For Android 11 Activity/WebView integration on an emulator or connected test device:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.retrosearch.play.test/com.retrosearch.play.SmokeInstrumentation
```

The integration test launches the real Activity and WebView, then uses an explicitly synthetic EmulatorJS fixture. It does not run a ROM or prove gameplay. The GitHub workflow builds, runs unit tests and lint, attempts Android 11 integration, and saves the APK and test evidence separately.

## Fire TV installation and remaining hardware checks

Enable ADB debugging on the Fire TV, connect it to your computer, then run:

```sh
adb connect FIRE_TV_IP:5555
adb install -r app-debug.apk
adb shell monkey -p com.retrosearch.play -c android.intent.category.LEANBACK_LAUNCHER 1
```

If installation reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, an older copy has a different signing key. Uninstalling that copy permits installation but deletes its app data. This testing build does not use an Appstore production signing key.

Confirm the TV launcher tile and banner, remote-only search/navigation, live player start, audio, game movement, face/shoulder buttons, Start/Select, diagonals and simultaneous inputs. Pair and test Xbox and PlayStation controllers separately; check reconnection, Back/Menu/Home, sleep/resume, and at least 15 minutes of play. These checks require the actual hardware.

## Network and trust boundary

The catalog is local and works offline; online playback requires SNESLive's game metadata and emulator assets, a compatible WebView, and internet access. Site changes or unavailable assets can prevent loading.

The native player reads the game page as data and copies only a validated SNES ROM URL into the bundled `game.html` template. It never executes the original page's HTML or scripts. Emulator advertisements are disabled. The player accepts only HTTPS emulator assets under `/emulator/data/` and ROM files under `/emulator/roms/` on the two exact SNESLive hosts, plus versioned runtime assets from the official EmulatorJS CDN for core-download fallback. Advertising hosts, unrelated first-party resources, frames, videos, and external navigation are blocked by both native request filtering and the document's content policy. If metadata or a download is unavailable, the app shows an error and Games/Reload controls.

The local catalog's Android bridge is isolated from network navigation. The game-only document and temporary search WebView have no Android JavaScript bridge; file access is disabled and cleartext/mixed content is blocked. The hidden search WebView reads main-document links while blocking all subresources, and is destroyed after returning results. Third-party cookies are disabled. The in-app browser fallback was removed to keep game launches inside the ad-free flow.

Independent project; not affiliated with SNESLive, Amazon, Nintendo, Microsoft, or Sony. Use games you have permission to access.

Device reference: https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html

## Version 1.3.0 — No ads

Game launches use a clean emulator document with direct startup instead of the website page and its pre-roll. Full-collection search, featured games, remote navigation, Xbox/PlayStation mappings, lifecycle handling, and full-screen layout are retained. Startup polling activates a visible emulator Start button at most once. Missing metadata and failed downloads show an app error.

The app and its synthetic tests do not prove live gameplay. In the October 28, 2026 cloud-browser check, Super Mario World reached the emulator but failed with a WebGL context error (GLctx). This does not establish Fire TV behavior. Physical Fire TV playback, audio, performance, and Bluetooth controller pairing remain unverified. No ROMs or emulator binaries are included.
