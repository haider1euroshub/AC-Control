# Audit of the existing project (2026-11-11) and changes made

> Historical audit of version 2.0/2.1. Since 2.2.0 the Haier key is fetched automatically from the
> HaiSmart account and the label scanner was removed; see PROGRESS.md for the current state.

## What was present

| Area | Found | Assessment |
|---|---|---|
| Build | Gradle 8.9, AGP 8.5.2, Kotlin 2.0.20, JDK 17, compileSdk/targetSdk 34, minSdk 26; modules `app`, `core-domain`, `core-protocol`, `driver-haismart`, `driver-smartair2`, `driver-hon` | Sound; kept |
| UI | Jetpack Compose + Material 3, MVVM with StateFlow; two screens (controls, setup) | Kept the stack and theme; screens rebuilt (see below) |
| `driver-haismart` | JSON payload such as `{"power":1,"mode":"cool",...}`, AES-CBC with the key used as raw hex bytes, `55 AA` framing | **Fabricated.** None of this matches the real protocol: no handshake, wrong key derivation, wrong framing, invented JSON. It could never have worked. |
| `driver-smartair2` | Unencrypted `FF FF` frames for the older SmartAir2 dongles | A different protocol; the target AC is confirmed to speak uSS (see PROTOCOL.md). Dead code. |
| `driver-hon` | Stub returning "not verified" | Placeholder. hOn modules do not listen on 56800 at all. |
| `SimulatedAcDriver` (debug) | Fake AC in debug builds | Simulated control, against the requirements |
| `DriverConnectionManager` | Persistent socket with a reader loop | Wrong model for this AC: sessions are capped at ~17 s and one at a time |
| State model | Values tagged CONFIRMED / OPTIMISTIC / OWNER_ASSERTED; an "owner-asserted baseline" let the user type in the AC's state | Optimistic and asserted state are not the AC's real state; removed |
| Setup | User typed IP, port, protocol driver and key | No discovery, single device, IP mandatory |
| Storage | Keystore AES-GCM for one config; fails closed | Good principle; reused for per-device keys |
| Diagnostics | Ring-buffer logger with secret redaction | Kept |
| Missing entirely | Discovery, device identity, multiple ACs, barcode scanner, fan speed, Turbo/ECO/Sleep/self-clean, command read-back verification, Wi-Fi state handling, rediscovery after IP change | Implemented |
| Docs | ~30 documents, many claiming "SOFTWARE_READY" for the fabricated drivers | Removed (recoverable from git history) and replaced with docs that match the code |

## Main gaps found

1. No real implementation of the AC's local protocol.
2. No discovery: the IP had to be typed in, and a DHCP change broke the app.
3. No device identity check before sending commands.
4. Commands could show "optimistic" success without the AC confirming them.
5. Only power, cool/heat, setpoint and vertical swing existed.
6. Single device only; no scanner; no local-key workflow.

## What was done

- **Protocol.** Investigated `enapt/haismart-local` (MIT) and ported its uSS transport to Kotlin:
  handshake, MD5-derived AES key, biz-data integrity check and nonce trailer, EPP frames with
  escaping, CAE envelopes, UDISCOVERY, the classic status layout and the hardware-confirmed group-set
  fields. Attribution is in `THIRD_PARTY_NOTICES.md`.
- **Confirmed on the target AC (key-free):** it answers UDISCOVERY with a uPlusId identical to
  haismart-local's verified "classic" reference profile, and accepts a uSS handshake for its device
  ID. See PROTOCOL.md.
- **Architecture.** Kept the module split; replaced the fabricated driver and the persistent-socket
  manager with a short-session client (`HaierUssClient`) and a connection coordinator
  (`AcConnectionCoordinator`) that owns the connection states, a single-session lock, polling,
  rediscovery and command verification. Removed `driver-smartair2`, `driver-hon` and the simulator.
- **App.** Multi-device storage keyed by device ID with Keystore-encrypted local keys; discovery-based
  pairing wizard; camera label scanner (ZXing, fully offline); dashboard for every confirmed control;
  device settings; diagnostics; help.
- **Honesty rules in code.** Commands are confirmed only when a status report read back from the AC
  shows the change. Controls appear only when the unit's report and its profile support them.
  Unrecognised report layouts are read-only. Each control is marked unverified until it has been
  confirmed on this unit.
- **Tests.** 106 automated tests: protocol vectors from the spec, a HELLO_RESP captured from the AC,
  an independently computed AES vector, full sessions against a fake AC over real sockets,
  coordinator state machine, storage, parser and validation. Plus an opt-in hardware smoke test.

## Plan for what remains

1. Obtain the AC's local key (SETUP_GUIDE.md) and run the hardware smoke test's status read.
2. Install the APK on a phone on the same Wi-Fi and run the real-device matrix in TEST_REPORT.md.
3. If the AC's status report is not the 125/127-byte classic layout, capture it with the
   diagnostics export and add its layout before enabling control.

---

# Audit against the universal requirements (2026-11-12)

The requirements changed from "one Haier model" to a universal multi-brand remote
(docs/REQUIREMENTS.md). The 2.0 code above was audited again for that goal. The summary and plan are
in [PROGRESS.md](PROGRESS.md). Main findings: the layering was sound, but the coordinator, capability
rules, storage and UI assumed Haier. These were generalised behind `AcProtocolAdapter` without
removing any working feature. Gree was added as the second integration.
