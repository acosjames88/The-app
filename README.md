# SNES Search & Play (Android / Fire TV)

A small Android app that searches a built-in list of popular SNESLive titles and opens the selected game's online player inside the app. The **Search All Games** button opens SNESLive search results inside the in-app browser. No games or ROM files are included.

## Install
1. Visit **Actions > Build APK** in this repository.
2. Open the latest successful run and download the **SNES-Search-Play-APK** artifact ZIP.
3. Extract **app-debug.apk**, then sideload it to Android phone/tablet or Fire TV Stick.

This is a debug-signed APK for personal testing, not a Play Store release. It needs internet access. WebView/Fire TV controller compatibility, third-party ads or site playback are not guaranteed. An optional Bluetooth game controller may be needed.

## Build yourself
Run `gradle :app:assembleDebug` with JDK 17 and Android SDK 35 / build-tools 35.0.0.
