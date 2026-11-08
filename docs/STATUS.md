# System Status & Acceptance Verification

**Target Commit / Base:** `399997bf9aba1a8702eb9aa9f7371f294b9c8695`
**Branch:** `recovery/software-ready`
**Date:** 2026-11-08
**Verification Mode:** `SOFTWARE_READY` (Verified without hardware connection)
**Hardware Status:** `HARDWARE_VERIFIED = false` (Awaiting physical Haier AC unit)

---

## 1. Acceptance Criteria Status Matrix

| ID | Criteria | Status | Evidence & Code References |
|---|---|:---:|---|
| **AC-1** | **No Fabricated Defaults** | **PASS** | `SmartAir2Driver.kt` and `HaiSmartDriver.kt` no longer substitute mode=COOL, temp=24, or swing=OFF. If any required passthrough field is unknown, commands return `CommandResult.Failed(AcError.CommandBlocked)`. |
| **AC-2** | **Provenance & Staleness** | **PASS** | `StateProvenance` includes `CONFIRMED_BY_AC`, `STALE`, `OPTIMISTIC_AFTER_SEND`, `OWNER_ASSERTED`, `UNKNOWN`. Disconnection marks all confirmed state as `STALE`. `ValueWithProvenance.isKnown` rejects STALE as a control basis. |
| **AC-3** | **Command Safety Gate** | **PASS** | `DefaultAcRepository.kt` blocks any mutating command (mode, temp, swing) when power is `UNKNOWN` or `STALE`. Verified by `DefaultAcRepositoryTest`. |
| **AC-4** | **Conservative Capabilities** | **PASS** | Heating disabled by default; horizontal swing defaults to `OFF`; temperature bounds clamped to [16.0, 30.0]. |
| **AC-5** | **Stepping & Debounce Protection** | **PASS** | `AcController.kt` coalesces rapid ± taps over 350ms window without interrupting in-flight network requests. Verified by `AcControllerTest`. |
| **AC-6** | **Single-Reader / Writer Transport** | **PASS** | `DriverConnectionManager.kt` uses coroutine `Mutex` for command execution and structured cancellation on EOF/close. |
| **AC-7** | **Frame Boundary & Accumulation** | **PASS** | `FrameCodec` boundary detection handles split and concatenated TCP packets, discarding malformed prefixes. |
| **AC-8** | **Single Shared Layout Table** | **PASS** | `SmartAir2Layout.kt` provides single source of truth for command IDs, checksum, and byte offsets. |
| **AC-9** | **HaiSmart Cryptographic Safety** | **PASS** | `HaiSmartCipher.kt` validates 16-byte local key, uses AES-128-CBC with PKCS5 padding, rejects truncated payloads. |
| **AC-10** | **hOn Honest Stub** | **PASS** | `HonDriver.kt` safely blocks all LAN traffic, returning typed `AcError.ProtocolNotVerified("hOn")`. |
| **AC-11** | **Storage & Secrets Redaction** | **PASS** | `EncryptedConfigStorage.kt` uses AES-256-GCM without plaintext fallbacks. `DiagnosticsLogger.kt` scrubs 32/64-hex keys and tokens without partial reveal. |
| **AC-12** | **Unsigned Release APK by Default** | **PASS** | `app/build.gradle.kts` does NOT use debug signing for release. R8 minification enabled (`app-release-unsigned.apk` is 1,349,156 bytes). |
| **AC-13** | **Zero Test Failures** | **PASS** | 37 unique unit tests across all 6 modules passing cleanly (0 failures, 0 errors, 0 skipped). |

---

## 2. Test Verification Summary (From JUnit XML Results)

- `:core-domain` (`AcControllerTest`): 5 tests, 0 failures, 0 errors
- `:core-protocol` (`DefaultAcRepositoryTest`): 5 tests, 0 failures, 0 errors
- `:driver-smartair2` (`SmartAir2DriverTest`): 8 tests, 0 failures, 0 errors
- `:driver-haismart` (`HaiSmartDriverTest`): 7 tests, 0 failures, 0 errors
- `:driver-hon` (`HonDriverTest`): 6 tests, 0 failures, 0 errors
- `:app` (`DiagnosticsLoggerTest`, `ErrorMappingTest`): 6 tests, 0 failures, 0 errors
- **Total Tested:** 37 tests, 0 failures, 0 errors.

---

## 3. Artifact Verification

- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (19,225,201 bytes)
- **Release APK:** `app/build/outputs/apk/release/app-release-unsigned.apk` (1,349,156 bytes, R8 minified, unsigned)

