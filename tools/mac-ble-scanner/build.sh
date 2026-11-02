#!/bin/sh
set -eu
cd "$(dirname "$0")"
app="${TMPDIR:-/tmp}/CarDroid BLE Scanner.app"
mkdir -p "$app/Contents/MacOS"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>mac-ble-scanner</string>
<key>CFBundleIdentifier</key><string>com.cardroid.tools.ble-scanner</string>
<key>CFBundleName</key><string>CarDroid BLE Scanner</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>1</string>
<key>NSBluetoothAlwaysUsageDescription</key><string>Scan nearby Bluetooth advertisements for CarDroid compatibility research.</string>
</dict></plist>
PLIST
xcrun swiftc Scanner.swift -o "$app/Contents/MacOS/mac-ble-scanner" -framework CoreBluetooth
printf '%s\n' "$app/Contents/MacOS/mac-ble-scanner"
