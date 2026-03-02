<p align="center">
  <img src="https://github.com/user-attachments/assets/188c42f8-d249-4a72-b27a-e2b4f10a00a8" alt="Bitchat Android Logo" width="420" />
</p>

> [!WARNING]
> This project has not completed an external security audit. Treat it as experimental and avoid sensitive/high-risk use.

# bitchat for Android

Android implementation of **bitchat**, a decentralized peer-to-peer messenger that works over Bluetooth mesh.

- No phone numbers
- No central server for mesh chat
- Cross-platform protocol compatibility with the iOS app
- Optional internet-backed geohash channels for local-area discovery

## What This Repo Is

This repository contains the Android app source code (`com.bitchat.droid`) built with Kotlin, Jetpack Compose, and Gradle.

## Install Prebuilt App

If you only want to use the app (not develop):

- GitHub Releases: [permissionlesstech/bitchat-android releases](https://github.com/permissionlesstech/bitchat-android/releases)
- Google Play: [com.bitchat.droid](https://play.google.com/store/apps/details?id=com.bitchat.droid)

---

## Developer Quick Start (Android Studio)

### 1. Prerequisites

Install the following:

- Android Studio (latest stable recommended)
- Android SDK Platform 35
- Android Build-Tools 35.x
- Platform Tools (`adb`)
- ADB-enabled Android device or an emulator (API 26+)

Important toolchain requirements used by this project:

- `compileSdk = 35`
- `targetSdk = 34`
- `minSdk = 26`
- Android Gradle Plugin `8.10.1`
- Kotlin `2.2.0`
- Gradle Wrapper `8.13`
- **JDK 17 is recommended** (use Android Studio bundled JDK/JBR)

### 2. Clone

```bash
git clone https://github.com/adewoleeugene/bitchat.git
cd bitchat
```

### 3. Open in Android Studio

1. Open Android Studio.
2. Click **Open** and select this repo folder.
3. Let Gradle sync.
4. In Android Studio settings, ensure Gradle uses **Embedded JDK** (or another JDK 17).

### 4. Set SDK Path (if needed)

If Android Studio does not auto-detect the SDK, create `local.properties` in project root:

```properties
sdk.dir=/Users/<your-username>/Library/Android/sdk
```

(macOS path shown above)

### 5. Run the App

1. Start an emulator or connect an Android phone via USB.
2. Confirm device visibility:

```bash
adb devices
```

3. In Android Studio, choose the `app` run configuration.
4. Click **Run**.

### 6. Testing with Two Emulators

For local mesh/chat testing, run **two Android emulators at the same time** and install the app on both.

Recommended approach:

1. Create two AVDs in Android Studio Device Manager (API 26+).
2. Launch both emulators.
3. Run/install `app` on emulator #1, then emulator #2.
4. Use each emulator as a separate user and validate message flow between them.

---

## CLI Build Commands

If you prefer terminal-based builds:

```bash
# Use Android Studio bundled JDK on macOS
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

Install to connected device:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
./gradlew :app:installDebug
```

APK output path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## Common Setup Errors

### `SDK location not found`

Fix one of the following:

- Configure Android SDK path in Android Studio, or
- Add `sdk.dir=...` to `local.properties`, or
- Export `ANDROID_HOME` / `ANDROID_SDK_ROOT`

### Build fails on JDK 21/25+

Set Gradle JDK to Android Studio embedded JDK (JDK 17).

### `adb devices` shows no devices

- For physical phone: enable Developer Options + USB debugging
- For emulator: create/start one from Device Manager

---

## Permissions and Hardware

Required runtime permissions:

- Bluetooth
- Nearby devices / location-related BLE permissions (varies by Android version)
- Notifications

Hardware/software minimum:

- Android 8.0+ (API 26)
- BLE-capable device

---

## Core Features

- Bluetooth LE mesh networking with multi-hop relay
- End-to-end encrypted private messaging
- Channel-based conversations with optional password protection
- Store-and-forward delivery for offline peers
- Optional geohash/location channels over internet
- Message retention controls and moderation-style channel ownership commands

---

## Useful Docs

- Architecture overview: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- File transfer behavior: [docs/file_transfer.md](docs/file_transfer.md)
- Sync details: [docs/sync.md](docs/sync.md)
- Source routing: [docs/SOURCE_ROUTING.md](docs/SOURCE_ROUTING.md)
- Remote dev setup: [docs/remote-dev-setup.md](docs/remote-dev-setup.md)

---

## License

Public domain. See [LICENSE.md](LICENSE.md).
