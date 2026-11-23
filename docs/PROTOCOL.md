# Protocol and authentication approach

## Source

Haier's local LAN protocol is undocumented. The implementation follows the reverse-engineered,
hardware-tested reference **haismart-local** (<https://github.com/enapt/haismart-local>, MIT; see
`THIRD_PARTY_NOTICES.md`), ported to Kotlin in `driver-haismart/`. Its `PROTOCOL.md` is the
detailed reference; this page records what this app uses and the evidence for this AC.

## Evidence from the target AC (2026-11-11, from a PC on the same /24 network)

Key-free and read-only: nothing was written to the AC.

| Probe | Result |
|---|---|
| UDISCOVERY search (UDP 7083) to 192.168.18.68 | 309-byte device-info reply. Device ID `540853BD1D41`, uPlusId `2008610800820324021200118012560000000000000000000000000000000040`, uSS port 56800, SDK `2.18`, module firmware `e_4.3.00 / R_6.0.01`, protocol tag `UDISCOVERY_UWT`, cloud state `1000` (connected to Haier cloud) |
| Subnet scan from 192.168.18.139/24 with no address given | Found the AC in 4.1 s (also found a second Haier appliance, `…B4CA`, at .29) |
| uSS HELLO (TCP 56800) for device ID `540853BD1D41` | `HELLO_RESP` with status 1 (session accepted), a session ID assigned, **local-key version 50** |

Conclusions:

- The AC speaks the **uSS** protocol of the Haier/HaiSmart (U+ SE-Asia) platform, not SmartAir2 or hOn.
- Its uPlusId is **identical** to haismart-local's reference unit `AAC1UKZ01` (shared wall-split
  profile `0201203a`), whose classic 125/127-byte status layout and group-set positions were
  confirmed there one attribute at a time on hardware.
- Status and control require the per-device local key (version 50 at the time of the test).

The same probes were also run through the app's own Kotlin code (`HardwareSmokeTest`), with the same
results.

## Layers

1. **Discovery (UDP 7083, no key).** `"Haier"` magic, search command `0x6915`, answered by `0x684D`
   with device ID, uPlusId, IP, uSS port, firmware and cloud state. Broadcast (from source port
   7083) plus unicast sweep of the phone's subnet, nearest addresses first, paced at 4 ms.
2. **uSS session (TCP 56800).** `HELLO(deviceId)` → `HELLO_RESP(status, session, keyVersion)` →
   `HELLO_DONE` → `HELLO_DONE_RESP` (encrypted body = op sequence base). Status ≠ 1 means the AC
   refused the session (wrong device ID). A key-version mismatch is detected here, before decrypting.
3. **Encryption.** AES-128-CBC, zero IV, key = MD5 of the 32-character local key as ASCII text.
   Each payload carries an MD5 integrity check, so a wrong or rotated key is detected and never
   produces decoded data. Outbound ops carry a 5-digit ASCII nonce trailer.
4. **Ops.** CAE request envelope (`0x2714`, device ID, counter) around an EPP frame
   (`FF FF | len | addr(6) | type | cmd(2) | data | checksum`, with `FF → FF 55` escaping).
   - Read: `getAllProperty` `4D01`. The AC answers with a type-02 status report and also pushes a
     type-06 report; the answer is preferred.
   - Write: group-set `6001` carrying the unit's whole control-word block, seeded from its own
     current report, so only the requested field changes. If the module's pushed report lags the
     AC's own answer, writes seed from the answer instead.
5. **Status report (classic layout).** Control words from byte 92: setpoint, vertical vane, mode, fan,
   on/off and flags (health, turbo, quiet, sleep, display), horizontal vane + ECO level, self-clean;
   then indoor (÷2) and outdoor (−64) temperatures, a cooling-only flag, and a fault code. 127-byte
   (6 words) and 125-byte (5 words) layouts are writable; any other length is shown read-only.

## Command verification

A command counts as done only when a status report from the AC shows it:

| AC response | App result |
|---|---|
| Status report with the change | **Confirmed** (dashboard updates from that report) |
| Refusal frame (type 03 + reason code) | **Rejected**, with the code |
| Status report without the change | **Rejected** |
| Nothing on the command's connection | A fresh status read decides: confirmed or rejected |
| Connection lost after sending | "Outcome unknown", then re-sync |

## Confirmed write fields and values

Only fields observed to take effect on hardware with this uPlusId (per haismart-local) are encoded:

| Control | Field (word, bit, width) | Values |
|---|---|---|
| Setpoint | 1, 8, 8 | °C − 16, 16–30 °C |
| Up/down swing | 1, 0, 4 | 0 fixed, 12 auto |
| Mode | 2, 13, 3 | 0 auto, 1 cool, 2 dry, 4 heat, 6 fan |
| Fan | 2, 8, 3 | 1 high, 2 medium, 3 low, 5 auto |
| Power / Health / Turbo / Quiet / Sleep / Display | 3, bits 0/1/3/4/5/9 | 0/1 |
| Left/right swing | 4, 0, 3 | 0 fixed, 7 auto |
| ECO level | 4, 3, 3 | 0 off, 5/6/7 = L1/L2/L3 |
| Self-clean | 5, 4, 1 | 1 = start (no stop command exists) |

Rules applied: Turbo and Quiet are mutually exclusive; entering Fan mode with Auto fan selects Low
(the AC drops fan-only + auto fan); self-clean is refused while off, in Auto, or with Sleep on.

**Not implemented, because nothing confirms them for this unit:** fixed louvre positions,
half-degree setpoints, timers, "I feel", fresh air, electric heat, humidity, and the
extended-status energy telemetry.

## Authentication and key handling

- The local key comes only from Haier's cloud (per account, per device). It is not derivable from
  the serial number, barcode or MAC.
- The app does **not** contain Haier account login or Haier app credentials. The user enters a key
  obtained once (see SETUP_GUIDE.md). This keeps the APK free of undocumented vendor credentials.
- Keys are stored AES-256-GCM-encrypted under a non-exportable Android Keystore key, excluded from
  backups, never logged (the logger redacts 32/64-hex strings), and deleted with their device.
