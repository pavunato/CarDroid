# Build

## Prerequisites

- A development environment with Java and Android SDK command-line tools
- JDK 17 (JDK 18 works in practice; JDK 21+ has not been tested)
- Android SDK with platforms `android-34` and `build-tools 34.0.0+`
- Gradle 8.9+ (the wrapper in this repo is pinned to 8.9)
- An Android device on API 26+ with USB debugging on, or an emulator

## Environment

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
```

Use the included Gradle wrapper. Configure your local SDK through the environment above or an untracked `local.properties` file.

## Build

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Run the JVM regression suites with Java 17 and the same SDK:

```bash
./gradlew :app:testDebugUnitTest :tools-runner:test
```

Unit tests and APK assembly do not validate rendering, input injection, third-party app launch modes, or receiver behavior. Those require a phone with the Shizuku/ADB helper and a receiver (or appropriate device test harness).

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run

The app needs these runtime permissions, granted via the in-app
"Grant permissions" chip:

- `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN` (API 31+)
- `ACCESS_FINE_LOCATION` (BLE scan prereq)
- `NEARBY_WIFI_DEVICES` (API 33+, for the hotspot)
- `POST_NOTIFICATIONS` (foreground service notification)

On Android 14+, the first car session makes the helper create a companion association for `com.android.shell` (MAC `02:ca:4d:00:00:01`). It lets car apps keep running while the phone is locked (see [architecture](ARCHITECTURE.md)). To remove it:

```bash
adb shell cmd companiondevice disassociate 0 com.android.shell 02:ca:4d:00:00:01
```

## Build flavors

Only the debug variant is wired today. To produce a release APK:

1. Generate a keystore: `keytool -genkey -keystore release.jks -alias cardroid -keyalg RSA -keysize 2048 -validity 10000`
2. Add a `signingConfigs` block to `app/build.gradle.kts`
3. `./gradlew :app:assembleRelease`

## Troubleshooting

**"Compose Compiler Gradle plugin is required"**
You're on Kotlin 2.0+. The root `build.gradle.kts` already declares
`org.jetbrains.kotlin.plugin.compose`; if you've forked and removed it, put
it back.

**"Member name 'equals' is reserved for future releases"**
You're trying to use `@JvmInline value class` with `equals`/`hashCode`
overrides. Use a regular class — see `mfi-bridge/HexBytes.kt`.

**"Unable to strip the following libraries"**
Harmless warning from AGP about `libandroidx.graphics.path.so`. Ignore.

**Build hangs on `:app:mergeExtDexDebug`**
First-build symbol-table generation can be slow on cold daemons. Wait it
out (≤2 min) or run with `--no-daemon` once to reset.

**`gradlew: Permission denied`**
`chmod +x gradlew`

**APK installs but service crashes on start**
Almost always a permissions issue. Check `adb logcat -s CarDroidService`
for the missing permission and grant it via Settings → Apps → CarDroid.
