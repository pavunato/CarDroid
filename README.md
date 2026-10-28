# CarDroid

**Android apps on your car's CarPlay screen.** Wireless, with a side-by-side launcher, widgets, and an app grid built for the car display. Independent app: `com.cardroid.app`.

> **Compatibility scope:** CarDroid is in active development. Results depend on the phone, Android version, and car receiver; device, keyboard, and display lifecycle compatibility still need broader validation.

[Download](https://github.com/pavunato/CarDroid/releases/latest) · [Telegram](https://t.me/+nZx6IUk47ENjN2Nl) · [Report a problem](https://github.com/pavunato/CarDroid/issues)

![CarDroid launcher: side-by-side apps, customizable widgets, and app grid](docs/images/cardroid-launcher-preview.svg)

*Layout illustration based on the current UI; app content is illustrative. Home keeps two apps side by side, the small indicators open the app picker, and the other pages provide customizable widgets and an app grid.*

## What you can do with CarDroid

- **Run Android apps on the car screen.** Home shows two apps side by side, such as YouTube and Maps, on a separate display from the phone.
- **Switch and reload panel apps.** Tap the small indicator above a panel to pick another app (recently used apps are listed first) or reload the current one.
- **Customize the launcher.** Arrange widgets, browse every app in the app grid, and return to recent apps from the side rail.
- **Keep navigation visible.** Grid, widget, recent-app and mapped receiver shortcuts open one app inside launcher-owned content beside the persistent rail, not over the car display. Back goes to that app; Home returns to the two-panel dashboard. There is no floating Home button.
- **Hear phone audio through the car speakers.** Media, navigation, and assistant audio play in the car at full volume, independent of the phone's volume.
- **Control apps from the car.** Car touchscreen and media keys are routed back to the apps on the car display.
- **Connect wirelessly.** CarDroid sets up the session over Bluetooth, then moves media to Wi-Fi, and can start automatically when Bluetooth connects.
- **Use the shell display helper.** Start it through Shizuku or ADB; root and Android Accessibility “Touch control” are not required. Phone mirroring is currently disabled—there is no screen-capture fallback if the helper is unavailable.

Recent apps are launcher session history, not the Android task switcher. Dashboard panels stay mounted when another page or single app is shown, with input disabled while covered. Android apps with restrictive launch modes (for example `singleTask`), secure video, or secondary-display limitations may not support independent instances; validate your apps on a device.

## Build and test

Configure Java 17 and the Android SDK as described in the [build guide](docs/BUILD.md), then run:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :tools-runner:test
```

Install the debug build:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Signed release builds read a gitignored `keystore.properties` at the repository root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it, `./gradlew :app:assembleRelease` produces an unsigned APK.

## Documentation

- [Roadmap](docs/ROADMAP.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Protocol notes](docs/PROTOCOL.md)
- [Receiver compatibility](docs/CAR-COMPATIBILITY.md)
- [Capture workflow](docs/CAPTURE.md)
- [Brand assets](docs/brand/README.md)

## Support

Questions, setup help, or bug reports: join the [CarDroid Telegram group](https://t.me/+nZx6IUk47ENjN2Nl).

## Author

Created by [pavunato](https://github.com/pavunato). If CarDroid is useful to you, please [star it on GitHub](https://github.com/pavunato/CarDroid).

## Acknowledgments

We learned from public protocol research, receiver implementations, and interoperability experiments. These references informed our understanding of discovery, authentication, media transport, and input handling.

CarPlay, iAP2, and MFi are Apple trademarks. CarDroid is an independent development project and is not affiliated with Apple.
