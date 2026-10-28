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

## Troubleshoot a car that sees only an ordinary Bluetooth phone

The **Setup checklist** checks phone prerequisites only. Even all-green permissions, Bluetooth and Shizuku checks do **not** verify CarPlay. Open **Car connection · live checks**, then **Show connection details**:

- **Full BLE advertisement active** appears only after Android reports advertising success. It is local proof, not proof that the car recognized the phone.
- **Reduced Bluetooth discovery** is an amber warning: Android rejected the full advertisement and the manufacturer-only fallback started. The fallback has **no iAP2 UUID in BLE**; Classic SDP is separate. This may affect receiver discovery.
- **Classic iAP2 listener** means `listenUsingRfcommWithServiceRecord` actually succeeded (SDP / RFCOMM). A listening socket is not a receiver connection.
- **Receiver Bluetooth socket accepted**, **iAP2 detect**, and **link synchronized** are separate observed events. Ordinary phone pairing does not imply any of these.
- Certificate, authentication, identification and EA-session events show what this implementation actually processed. “Authentication response processed locally” is not a cryptographic compatibility guarantee or a receiver-acceptance claim.
- **Wi-Fi configuration received** is not **Accessory Wi-Fi joined**. **AirPlay endpoint received** is not media ready. **RECORD accepted** and **Media transport ready** have their own real handshake hooks. There is no receiver acknowledgement proving video is visibly rendered; check the car screen.
- Missing signals stay **Not verified**. Stopping clears live proof; a new receiver attempt clears prior session proof. Last issues and a bounded typed-event timeline are retained in the report for diagnosis.

Tap **Make phone discoverable** to open Android’s official confirmation for **120 seconds** of Classic Bluetooth visibility. Nearby devices / Bluetooth permission is requested first when needed. Visibility is read from the adapter’s real scan mode and system broadcasts, not inferred from BLE success or the countdown. The remaining time is approximate; Android controls expiry, and returning to paired-only visibility is normal. Denying the request is shown without claiming a protocol failure. The app does not bypass OS consent or enable permanent discoverability, and background auto-start never opens this dialog. Use **Retry connection checks** to tear down the old attempt and start a fresh generation, or **Stop checks** to clear live proof.

Select this phone from the car’s **CarPlay / add-phone screen**, not only its ordinary Bluetooth phone list. If it stalls, tap **Copy report** or **Share** and include the car model/year, receiver firmware and phone model manually. The report contains only allowlisted status/events, app and Android versions: no raw logs, SSIDs, addresses, Wi-Fi passwords, pairing keys, pinned keys, auth seeds or protocol payloads. Arbitrary exception text is deliberately omitted for privacy. Existing Advanced/raw on-device logs are separate and may contain sensitive protocol details; do not share them without review.

These diagnostics do not alter advertisement payloads or fix receiver compatibility by themselves. A real phone and car are required to verify discovery and projection.

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
