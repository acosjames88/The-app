# SNESLive TV Search & Play

Android TV / Fire TV WebView application with a searchable 57-game catalog and direct links to SNESLive's online player. Selecting a game opens its page and activates the site's visible EmulatorJS Start button after the site's own loading flow. No ROMs or emulator binaries are bundled.

Designed for Fire TV Stick 4K Max (2nd Gen, AFTKRT), Fire OS 8 / Android 11 / API 30. This is a universal Java APK with no ABI-specific native libraries and no Google Play Services dependency. Hardware compatibility and gameplay need confirmation on a physical Fire TV. Build success, synthetic input tests, and a loaded web page do not establish working gameplay.

## Build

Use JDK 17, Android SDK platform 35, and build tools 35.0.0. The wrapper pins Gradle 8.10.2 and verifies its distribution checksum; Android Gradle Plugin is 8.7.3.

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Installable output: `app/build/outputs/apk/debug/app-debug.apk`. Version 1.2.0 (code 2), package `com.retrosearch.play`, min SDK 23, target SDK 35. The APK is debug-signed for sideload testing.

## Controls

In the catalog, D-pad / left stick moves focus; Center / Xbox A / PlayStation Cross selects. Search filters the local list immediately. Enter with one result opens that game; with multiple results it focuses the choices. Search SNESLive opens the full website search.

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

Controller input uses Android's standard button positions, independent of the controller name. D-pad key and hat events and left-stick movement are handled, with a deadzone, duplicate-event merging, and release on focus loss, pause, and disconnect. The app sends SNES input IDs to the live EmulatorJS input API; browser gamepad input is gated while native controls are active to avoid duplicate presses. Returning to the site's controls restores its original input handler.

## Test

`./gradlew :app:testDebugUnitTest :app:lintDebug` runs controller unit tests and Android lint.

The browser suite checks catalog search, TV focus, visible Start activation, duplicate suppression, and release/resume against a synthetic input sink:

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

The catalog is local and works offline; online playback requires SNESLive, its emulator assets, a compatible WebView, and internet access. Site changes or restrictions can prevent loading. The app respects the site's normal loading flow. It does not bypass advertisements or access controls.

The local catalog's Android bridge is isolated from network navigation. External game pages have no Android JavaScript bridge, file access is disabled, cleartext/mixed content is blocked, and top-level in-app navigation is limited to SNESLive HTTPS origins. Open browser is a user-selected fallback.

Independent project; not affiliated with SNESLive, Amazon, Nintendo, Microsoft, or Sony. Use games you have permission to access.

Device reference: https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html

## Version 1.2.0

Full-collection search now returns game cards in the app rather than opening a website search page. A separate, temporary WebView reads only the main search results and exposes no Android JavascriptInterface. Query changes cancel stale searches; pages and network errors stay in the app. The selected online game still loads SNESLive's normal player and pre-roll. Once the runtime is ready, its game container fills the screen. Emulator startup errors are reported immediately.

The app and its synthetic tests do not prove live gameplay. In the October 28, 2026 cloud-browser check, Super Mario World reached the emulator but failed with a WebGL context error (GLctx). This does not establish Fire TV behavior. Physical Fire TV playback, audio, performance, and Bluetooth controller pairing remain unverified. No ROMs or emulator binaries are included.
