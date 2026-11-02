import Foundation
import CoreBluetooth
import Darwin

// CoreBluetooth reports decoded advertisement fields, not raw over-the-air packets.
final class Scanner: NSObject, CBCentralManagerDelegate {
    private var central: CBCentralManager!
    private let appleOnly: Bool
    private let matchID: String?
    private let seconds: Double

    init(appleOnly: Bool, matchID: String?, seconds: Double) {
        self.appleOnly = appleOnly
        self.matchID = matchID?.uppercased()
        self.seconds = seconds
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            fputs("Scanning BLE. Press Ctrl-C to stop. Output may contain sensitive nearby-device data.\n", stderr)
            central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
            if seconds > 0 {
                DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
                    central.stopScan()
                    exit(0)
                }
            }
        case .unauthorized:
            fputs("Bluetooth access denied. Enable access for the scanner or terminal in System Settings > Privacy & Security > Bluetooth.\n", stderr)
            exit(1)
        case .unsupported:
            fputs("Bluetooth LE is not supported on this Mac.\n", stderr)
            exit(1)
        case .poweredOff:
            fputs("Bluetooth is off. Turn it on to scan.\n", stderr)
            exit(1)
        case .resetting, .unknown:
            break
        @unknown default:
            fputs("Unknown Bluetooth state.\n", stderr)
            exit(1)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData data: [String: Any], rssi RSSI: NSNumber) {
        let id = peripheral.identifier.uuidString
        guard matchID == nil || matchID == id.uppercased() else { return }
        let manufacturer = data[CBAdvertisementDataManufacturerDataKey] as? Data
        if appleOnly && !(manufacturer?.starts(with: [0x4c, 0x00]) ?? false) { return }
        var event: [String: Any] = [
            "timestamp": ISO8601DateFormatter().string(from: Date()),
            "peripheral_id": id,
            "rssi_dbm": RSSI.intValue,
        ]
        if let name = data[CBAdvertisementDataLocalNameKey] as? String { event["local_name"] = name }
        if let manufacturer { event["manufacturer_data_hex"] = manufacturer.map { String(format: "%02x", $0) }.joined() }
        if let uuids = data[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] { event["service_uuids"] = uuids.map(\.uuidString) }
        if let uuids = data[CBAdvertisementDataOverflowServiceUUIDsKey] as? [CBUUID] { event["overflow_service_uuids"] = uuids.map(\.uuidString) }
        if let uuids = data[CBAdvertisementDataSolicitedServiceUUIDsKey] as? [CBUUID] { event["solicited_service_uuids"] = uuids.map(\.uuidString) }
        if let serviceData = data[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data] {
            event["service_data_hex"] = Dictionary(uniqueKeysWithValues: serviceData.map { ($0.key.uuidString, $0.value.map { String(format: "%02x", $0) }.joined()) })
        }
        if let txPower = data[CBAdvertisementDataTxPowerLevelKey] as? NSNumber { event["tx_power_dbm"] = txPower }
        if let connectable = data[CBAdvertisementDataIsConnectable] as? NSNumber { event["connectable"] = connectable.boolValue }
        guard let json = try? JSONSerialization.data(withJSONObject: event, options: [.sortedKeys]),
              let line = String(data: json, encoding: .utf8) else { return }
        print(line)
        fflush(stdout)
    }
}

func usage() -> Never {
    fputs("Usage: mac-ble-scanner [--apple-only] [--id PERIPHERAL-UUID] [--seconds N]\n", stderr)
    exit(2)
}
var appleOnly = false
var matchID: String?
var seconds: Double = 0
var args = Array(CommandLine.arguments.dropFirst())
while !args.isEmpty {
    let arg = args.removeFirst()
    switch arg {
    case "--apple-only": appleOnly = true
    case "--id":
        guard !args.isEmpty, let uuid = UUID(uuidString: args.removeFirst()) else { usage() }
        matchID = uuid.uuidString
    case "--seconds":
        guard !args.isEmpty, let value = Double(args.removeFirst()), value.isFinite, value >= 0 else { usage() }
        seconds = value
    case "--help", "-h": usage()
    default: usage()
    }
}
let scanner = Scanner(appleOnly: appleOnly, matchID: matchID, seconds: seconds)
RunLoop.main.run()
