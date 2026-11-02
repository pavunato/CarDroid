# Protocol notes

This is what's publicly known about iAP2 and CarPlay's Bluetooth phase, with
clear markers for which bytes are NDA-protected and how CarDroid handles them.

CarDroid keeps authentication separate from wireless transport, link synchronization, and control flow. Protocol behavior is validated through receiver interoperability testing.

## Layers

```
+---------------------------------------------+
| CarPlay protocol (control messages,         |
|   navigation metadata, audio session)       |
+---------------------------------------------+
| iAP2 Control Session  (40 40 SOM + TLV)     |
+---------------------------------------------+
| iAP2 Link layer       (FF 5A frames)        |
+---------------------------------------------+
| Bluetooth RFCOMM (Classic BR/EDR)           |
+---------------------------------------------+
| Bluetooth L2CAP                             |
+---------------------------------------------+
| Bluetooth HCI                               |
+---------------------------------------------+
```

## Discovery — public

| Item | Value |
|---|---|
| Apple Bluetooth SIG company ID | `0x004C` |
| Accessory iAP2 service UUID | `00000000-deca-fade-deca-deafdecacaff` |
| iPhone iAP2 service UUID | `00000000-deca-fade-deca-deafdecacafe` |
| CarDroid role | iPhone (so we expose `deafdecacafe`) |

CarDroid advertises Apple manufacturer-data + the iPhone service UUID via
BLE, and lists the iAP2 service in its SDP record so the head unit can pull
the RFCOMM channel number.

## Link-layer frame — public structure, NDA control bits

```
off  bytes  field
 0    2     header             = FF 5A
 2    2     total length       (BE; 9 if no payload, 10 + payload length if payload exists)
 4    1     control            = SYN(0x80), ACK(0x40), EAK(0x20), RST(0x10)
 5    1     sequence number    (per-direction running counter)
 6    1     acknowledgment     (last sequence we received)
 7    1     session id         (reference: 0 = control/auth, 1 = data, 2 = EA)
 8    1     header checksum    = (-sum(bytes[0..7])) & 0xFF
 9    N     payload            (only when total length > 9)
9+N   1     payload checksum   = (-sum(payload bytes)) & 0xFF
```

Source: `libcardroid/Iap2Frame.kt`.

reference notes that SYN appears during link synchronization and ACK is present on
most later packets. It also documents two checksums: one for the header and one
for the payload.

## Link Synchronization Payload — public structure, NDA defaults

The first packet on a new RFCOMM channel. CarDroid's encoder:

```
version(1) | maxOutgoing(1) |
maxRxPacketLen(2 BE) |
retransmitTimeoutMs(2 BE) | cumulativeAckTimeoutMs(2 BE) |
maxRetransmits(1) | maxCumulativeAcks(1) |
sessions[ id(1) type(1) version(1) ]...
```

There is no session-count byte; the number of session descriptors is implicit
from `(payload length - 10) / 3`.

Source: `libcardroid/Iap2LinkSync.kt`.

Defaults in `LinkSyncParameters` (replace with capture values):

| Field | Default | Source |
|---|---|---|
| version | 0x01 | observed |
| maxOutstanding | 0x7F | reference wireless client-mode extractor |
| maxRxPacketLen | 65535 | reference wireless client-mode extractor |
| retransmitTimeoutMs | 2000 | reference wireless client-mode extractor |
| cumulativeAckTimeoutMs | 1000 | reference wireless client-mode extractor |
| maxRetransmits | 30 | reference wireless client-mode extractor |
| maxCumulativeAcks | 1 | reference wireless client-mode extractor |
| Sessions | control/auth session 0 on the packet header; current LSP still advertises type-0/type-2 descriptors for compatibility | reference + captures |

## Control session (TLV) — public

Wraps inside frames addressed to session id 0 for the current reference-aligned
test. CarDroid also accepts the peer-advertised type-0 LSP session inbound.

```
off  bytes  field
 0    2     start-of-message   = 40 40
 2    2     length (BE, total including SOM)
 4    2     message id (BE)
 6    N     parameter blobs    [ paramLen(2 BE) paramId(2 BE) value ]...
```

### Public message-id catalog (R23+)

| ID | Direction | Name |
|---|---|---|
| `0x1D00` | iPhone → accessory | StartIdentification |
| `0x1D01` | accessory → iPhone | IdentificationInformation |
| `0x1D02` | iPhone → accessory | IdentificationAccepted |
| `0x1D03` | iPhone → accessory | IdentificationRejected |
| `0xAA00` | iPhone → accessory | RequestAuthenticationCertificate |
| `0xAA01` | accessory → iPhone | AuthenticationCertificate |
| `0xAA02` | iPhone → accessory | RequestAuthenticationChallengeResponse |
| `0xAA03` | accessory → iPhone | AuthenticationResponse |
| `0xAA04` | iPhone → accessory | AuthenticationFailed |
| `0xAA05` | iPhone → accessory | AuthenticationSucceeded |
| `0x5702` | iPhone → accessory | RequestAccessoryWiFiConfigurationInformation |
| `0x5703` | accessory → iPhone | AccessoryWiFiConfigurationInformation |
| `0x4E0D` | iPhone → accessory | WirelessCarPlayUpdate |
| `0x4E0E` | iPhone → accessory | DeviceTransportIdentifierNotification |
| `0xEA00` | accessory → iPhone | StartExternalAccessoryProtocolSession |
| `0xEA01` | accessory → iPhone | StopExternalAccessoryProtocolSession |
| `0xAE00` | accessory → iPhone | StartPowerUpdates |
| `0xAE01` | iPhone → accessory | PowerUpdate |

## MFi authentication — public protocol, hardware-gated

Auth proceeds as:

```
1. iPhone     ->  head unit: request MFi certificate (0xAA00)
2. head unit  ->  iPhone   : MFi certificate (0xAA01)
3. iPhone     ->  head unit: challenge bytes (0xAA02)
4. head unit  ->  MFi chip : challenge
5. MFi chip   ->  head unit: signature = RSA-PSS-SHA1/SHA256(challenge, Kpriv)
                              certificate = Apple-signed (Kpub, accessoryID)
6. head unit  ->  iPhone   : authentication response (0xAA03)
7. iPhone     verifies:
     - certificate signed by Apple MFi root CA (baked into head unit fw)
     - signature verifies against certificate's public key
8. iPhone     ->  head unit: AuthenticationSucceeded (0xAA05)
```

`Kpriv` is a per-chip RSA-2048 private key burned into the secure element
at Apple's fab. It cannot be read out. It can only be exercised via the
chip's I²C register interface. This is why a physical chip is required.

## CarPlay nav metadata — out of scope today

After auth + EA session, the iPhone streams navigation metadata frames to
the head unit so it can render turn arrows and ETA on the cluster. CarDroid
does not synthesize these today — adding it is in [ROADMAP.md](ROADMAP.md).

The relevant CarPlay message family is `0x4xx` on the External Accessory
session, but the field layout is NDA. Capture from a real iPhone running
Google Maps in CarPlay to reverse this.

## Sources

- <https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering> — Carlinkit firmware dumps
- <https://www.reference.security/blog/pwn-my-ride-exploring-the-carplay-attack-surface> — Pwn My Ride
- <https://developer.apple.com/videos/play/wwdc2017/717/> — Developing Wireless CarPlay Systems (WWDC17)
- <https://www.realmcu.com/en/blog/blog/1cb76284-3e00-40c2-b14c-70a0f9b584d2> — Realtek iAP2 BT overview
- Apple MFi Accessory Interface Specification R23+ — NDA, not reproduced here
