# Software Readiness & Hardware Testing Checklist

This document tracks the completion of software readiness criteria and identifies remaining items requiring the physical air conditioner.

---

## 1. SOFTWARE_READY Checklist (All Items Verified)

| Criteria | Status | Evidence / Verification |
|---|:---:|---|
| **No Fabricated Defaults** | **PASS** | State values track provenance via `StateProvenance`. Unknown values display as `--` or "Unknown". |
| **Command Safety Gate** | **PASS** | `DefaultAcRepository` blocks all mutating commands when power state is `UNKNOWN`. Verified by test. |
| **Conservative Capabilities** | **PASS** | Heating is disabled by default; swing limited to Off/Oscillate until confirmed by owner. |
| **Debounce Protection** | **PASS** | Temperature adjustments coalesce rapid taps over 350ms without cancelling in-flight transmissions. |
| **Single-Reader / Writer** | **PASS** | `DriverConnectionManager` serializes socket operations with coroutine `Mutex`. |
| **Frame Accumulation** | **PASS** | `FrameCodec` handles chunked/split TCP streams with boundary detection and checksum validation. |
| **Single Shared Layout Table** | **PASS** | SmartAir2 encoder and decoder share `SmartAir2Layout.kt`. Discrepancies resolved. |
| **HaiSmart Cryptographic Safety** | **PASS** | AES-128-CBC with strict key validation; truncated payloads return `null` instead of defaults. |
| **hOn Stub Honesty** | **PASS** | Driver returns `AcError.ProtocolNotVerified("hOn")`, safely blocking LAN traffic. |
| **Storage Hardening** | **PASS** | Removed plaintext Base64 fallback from `EncryptedConfigStorage`; fail-closed on Keystore error. |
| **Secrets Redaction** | **PASS** | `DiagnosticsLogger` scrubs 32/64-char hex keys, tokens, and passwords without partial reveal. |
| **Minimal Permissions** | **PASS** | `AndroidManifest.xml` stripped of `usesCleartextTraffic`, `CHANGE_NETWORK_STATE`, and `ACCESS_WIFI_STATE`. |
| **Passive Probe Tool** | **PASS** | Non-destructive connection probe implemented in `SetupScreen` with guaranteed socket closure. |
| **Unit Test Suite** | **PASS** | 32 unique tests passing cleanly across all 6 modules (0 failures). |
| **APK Compilation** | **PASS** | Debug APK (`15,746,634 bytes`) and Release APK (`10,214,687 bytes`) assembled on disk. |

**Verdict:** The application has achieved **SOFTWARE_READY** status.

---

## 2. HARDWARE_VERIFIED Checklist (Pending Physical Hardware)

> **Notice:** The following checklist cannot be completed without the physical Haier AC unit and is intentionally left pending.

| Feature / Hardware Action | Physical Hardware Status |
|---|:---:|
| **Physical Socket Handshake & Discovery** | ⏳ PENDING (Awaiting AC on local Wi-Fi) |
| **Power ON/OFF Beep & Response** | ⏳ PENDING (Awaiting AC on local Wi-Fi) |
| **Cooling / Heating Mode Switch** | ⏳ PENDING (Awaiting AC on local Wi-Fi) |
| **Temperature Increment / Decrement** | ⏳ PENDING (Awaiting AC on local Wi-Fi) |
| **Louver Fan Swing Movement** | ⏳ PENDING (Awaiting AC on local Wi-Fi) |
| **Wireshark Packet Trace Validation** | ⏳ PENDING (Awaiting AC on local Wi-Fi) |
