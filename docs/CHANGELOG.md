# Changelog

All notable changes made to the "Room AC Controller" project during this engineering overhaul are documented below.

## [1.1.0] - 2026-11-08

### Phase 1: Domain Safety Model
- **Added `ValueWithProvenance<T>` and `StateProvenance`**: Replaced unverified binary flags with explicit provenance tracking (`CONFIRMED_BY_AC`, `OPTIMISTIC_AFTER_SEND`, `OWNER_ASSERTED`, `UNKNOWN`).
- **Safety Gate Added**: `DefaultAcRepository` now rejects mode, temperature, and swing adjustments if the current power state is `UNKNOWN`.
- **Owner-Asserted Baseline**: Added `OwnerAssertedBaseline` model allowing users to bootstrap control when readback parsing is unverified.
- **Debounce Fix**: `AcController` now debounce-coalesces rapid temperature adjustments without dropping in-flight requests.
- **Conservative Capabilities**: Default capabilities now set `supportsHeating = false` and swing to `OFF`/`OSCILLATE` until verified.

### Phase 2: Robust Transport Engine
- **Added `DriverConnectionManager`**: Introduced single-reader, single-writer network engine with `Mutex` command serialization.
- **Frame Accumulation**: Added `FrameCodec` boundary detection preventing corrupted reads on chunked or split TCP packets.
- **Timeout & Backoff**: Added bounded exponential reconnect backoff and response correlation via `CompletableDeferred`.

### Phase 3 & 4: Protocol Drivers Overhaul
- **SmartAir2 Single Layout**: Created `SmartAir2Layout` unifying offsets and fields across encoder and decoder; corrected control vs. status offsets.
- **SmartAir2 Conservative Defaults**: Exposes verified swing states only and gates heating unless explicitly asserted.
- **HaiSmart Integration**: Integrated with `DriverConnectionManager` and AES-128-CBC cipher with validation of key lengths.
- **hOn Driver**: Replaced blind mock with safe stub returning `AcError.ProtocolNotVerified("hOn")`.

### Phase 5: Android Layer & Storage Hardening
- **Keystore Storage Hardened**: Removed Base64 plaintext fallback from `EncryptedConfigStorage`; fail-closed on Keystore exception.
- **Secrets Redaction**: `DiagnosticsLogger` updated with comprehensive regex redacting 32/64-char hex keys, passwords, and tokens without partial reveal.
- **UI Truthfulness**: `MainScreen` shows `--` for unconfirmed temperatures and displays `StateProvenance` chips.
- **Passive Connection Probe**: Added non-destructive probe tool in `SetupScreen` to inspect module banner/silence before transmitting frames.
- **Manifest Cleanup**: Removed unnecessary permissions (`CHANGE_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `usesCleartextTraffic`).

### Phase 6 & 7: Test Suite & Artifact Verification
- **Test Suite Validated**: Fixed ClassCastException in `DefaultAcRepositoryTest`, updated `SimulatedAcDriver`, and verified test suite across all modules.
- **APK Verification**: Produced fresh, verified Debug APK (`15,746,634 bytes`) and Release APK (`10,214,687 bytes`).
