# SNESLive TV 2.0 — Native select & play

Select a game and play it full screen. The catalog and player are native Android views. There is no WebView, website screen, advertisement, JavaScript engine, emulator download, or external browser handoff.

The APK bundles Snes9x 2005 for armeabi-v7a, arm64-v8a, and x86_64. Version 2.0.0 (code 5), package com.retrosearch.play, minimum Android 6/API 23, target API 35. Designed for Fire TV Stick 4K Max 2nd Gen / Fire OS 8. No Google Play Services dependency.

## Games

- **Star Catcher** is an original, public-domain SNES homebrew game bundled for immediate offline play. Move with the D-pad, collect yellow stars, and press SNES B for a tone. Its 65816 CPU program, graphics, SPC700 sound program, and ROM generator are in scripts/make-homebrew.py.
- Choose a catalog title to download its game data directly into the app and run it in the bundled native core. Downloaded games are cached for offline play. Availability depends on the source server; the app cannot make a missing game file available.
- **Import game** opens Android's file picker for .sfc, .smc, and ZIP files. Fire TV needs a file picker installed for this action. Only use games you have permission to access.
- ZIP contents are read in memory with size limits, never extracted to arbitrary filesystem paths.
- Battery-backed save RAM is saved automatically when the game pauses or closes. This version does not implement emulator save states.

No commercial ROM is bundled. Online game metadata is parsed as text; website scripts and advertising are never executed or displayed. Only validated HTTPS game-file addresses on the configured source hosts are accepted. HTML responses and invalid or oversized ROM/archive files are rejected before emulation. Errors are native dialogs; there is no webpage fallback.

## Controls

| Xbox | PlayStation | SNES |
| --- | --- | --- |
| A | Cross | B |
| B | Circle | A |
| X | Square | Y |
| Y | Triangle | X |
| LB / RB | L1 / R1 | L / R |
| Start / View | Options / Share | Start / Select |
| D-pad / left stick | D-pad / left stick | Move |

Remote: D-pad moves; Center = B, Rewind = Y, Fast Forward = A, Play/Pause = Start. Menu opens native pause controls and switches the remote bank to X / L / R / Select. Back pauses; choose Games to return to the catalog. A gamepad supports full simultaneous input. Input is merged across physical sources, and released on pause, focus loss, and disconnect.

## Build

Use JDK 17, Android SDK 35, NDK 27.2.12479018, and Gradle 8.10.2.

1. Run bash scripts/prepare-core.sh. This checks out the exact Snes9x 2005 revision a79dfe9047e7fec58808aefe48ad2bf499c7af11, copies the upstream notices, and generates the original homebrew ROM.
2. Run gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug.
3. The APK is app/build/outputs/apk/debug/app-debug.apk.
4. Run python3 scripts/verify-apk.py app/build/outputs/apk/debug/app-debug.apk to check native cores, offline ROM, notices, and absence of any browser/runtime assets.

The workflow uploads the installable APK, test results, and a corresponding-source archive containing the app plus the exact native core sources. Snes9x's upstream copyright and noncommercial distribution terms are included in the APK and available under Controls / About → Licenses. Core source: https://github.com/libretro/snes9x2005/tree/a79dfe9047e7fec58808aefe48ad2bf499c7af11 . The core is unmodified; it is compiled with the Blargg APU enabled. The original Star Catcher code and ROM are CC0.

## Verification scope

The Android instrumentation test executes the original SNES ROM in the actual bundled native core. It checks CPU-written SRAM, movement, star collection, changed video pixels, nonzero SPC700-generated PCM, save RAM persistence, the native Activity's Surface pixels, controller routing, pause, and a second game session. It does not substitute a JavaScript input fixture for a game.

The GitHub workflow also runs JVM controller/repository tests, Android lint, and APK packaging checks. See the linked run for actual results; this README does not itself claim they passed.

Actual Fire TV hardware, sustained performance, sound at physical speakers, Bluetooth Xbox/PlayStation pairing, and compatibility of individual third-party games require hardware checks. Android emulator results do not establish those.

## Sideload

The APK is debug-signed for personal sideload testing:

adb connect FIRE_TV_IP:5555
adb install -r app-debug.apk

An update signed by a different key may require removal of the installed copy; that deletes its app data. GitHub runner debug keys are not stable production signing keys.

Independent project, not affiliated with Amazon, Nintendo, SNESLive, Microsoft, or Sony.

