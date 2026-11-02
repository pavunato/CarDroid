# macOS BLE advertisement scanner

This local, long-running Core Bluetooth scanner prints one JSON object per discovery callback. It never connects to devices or sends data to a server. Stop it with Ctrl-C. It requires macOS, Xcode command-line tools, Bluetooth enabled, and Bluetooth permission for the scanner or the terminal that launches it.

Build and run (the build script prints the executable path):

```sh
scanner=$(./tools/mac-ble-scanner/build.sh)
"$scanner" --seconds 60 > ~/Desktop/cardroid-ble-scan.jsonl
```

Omit `--seconds 60` to scan until stopped. Add `--apple-only` to include only callbacks whose manufacturer data starts with Apple's Bluetooth company identifier. This does **not** prove a device is an iPhone; other Apple devices and devices using the same identifier can match, and iPhone advertisements without that field will be missed. `--id UUID` filters to a Core Bluetooth peripheral UUID found in an earlier scan. That UUID is assigned by macOS; it is not the Bluetooth MAC address and should not be assumed stable across Macs or privacy changes.

For a comparison, scan in a quiet area with permission from nearby device owners. Put the iPhone close to the Mac, run a short baseline, then use the iPhone's CarPlay setup near the car while capturing another short scan. Compare timestamps, signal strength and changes in the data; use a controlled baseline to identify candidates. Repeat separately with CarDroid, without `--apple-only` if it filters out the Android phone. Neither proximity nor manufacturer data alone proves which device produced an event. Keep the Mac awake and close enough to the devices during each scan.

The file contains device identifiers, names, manufacturer and service data from **nearby devices**, potentially including sensitive or trackable information. Store it privately, review and remove unrelated device records before sharing, and delete it when finished. The scanner does not write a file unless output is redirected. It does not submit data through CarDroid's sanitized report.

Core Bluetooth returns the fields macOS exposes, not a complete over-the-air packet capture. macOS may filter, merge or omit advertisement or scan-response information. Not seeing a field is not proof it was never transmitted. This tool does not capture Bluetooth Classic discovery, SDP, RFCOMM, iAP2 or authentication exchanges; a suitable hardware sniffer and an authorized capture setup are required to investigate those. Advertisement observations alone cannot prove CarPlay recognition or compatibility.
