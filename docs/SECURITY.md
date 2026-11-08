# Security & Cryptographic Assessment

**Describes Commit:** `399997bf9aba1a8702eb9aa9f7371f294b9c8695` + `recovery/software-ready` branch
**Date:** 2026-11-08

---

## 1. Local Storage Security (`EncryptedConfigStorage`)

1. **Android Keystore Backing:**
   - Cryptographic master key is generated via `AndroidKeyStore` provider.
   - Algorithm: `KeyProperties.KEY_ALGORITHM_AES` (256-bit).
   - Mode & Padding: `KeyProperties.BLOCK_MODE_GCM`, `KeyProperties.ENCRYPTION_PADDING_NONE`.
   - Dedicated master key alias: `room_ac_config_master_key`.

2. **No Insecure Fallbacks:**
   - Plaintext or Base64 storage fallbacks are explicitly prohibited.
   - If Keystore initialization or decryption encounters an error, the repository fails closed (`AcError.KeyUnreadable` / `AcError.NoAcConfigured`).

3. **Key Destruction on Reset:**
   - Clearing configuration deletes both the encrypted payload on disk and the key entry from the Android Keystore.

---

## 2. Network Protocol Cryptography & Limitations

### HaiSmart Driver (AES-128-CBC)
- **Algorithm:** AES-128 in CBC mode with PKCS5/PKCS7 padding.
- **Zero-IV Limitation (Public Protocol Specification):**
  - As documented in the public reverse-engineering references (`enapt/haismart-local`), the Haier HaiSmart protocol uses an all-zero 16-byte initialization vector (`0x00 * 16`).
  - **Risk:** Identical plaintext commands encrypted under the same local key produce identical ciphertext packets, making them susceptible to LAN replay attacks.
  - **Mitigation:** Control commands are strictly restricted to the local Wi-Fi subnet; the app prohibits non-loopback non-LAN addresses and operates entirely without cloud connections.

### SmartAir2 Driver (Plaintext Binary TCP)
- **Protocol:** Unencrypted TCP packet transmission over port 56800.
- **Header & Framing:** 2-byte sync header (`0xFF 0xFF`), big-endian length prefix, trailing modulo-256 checksum.
- **Mitigation:** Isolated to local trusted LAN; no cloud relay or remote exposure.

### hOn Driver (Honest Stub)
- **Status:** Unverified on physical hardware. All network transmissions are disabled; attempts to execute return `AcError.ProtocolNotVerified("hOn")`.

---

## 3. Secrets Redaction & Logging Policy (`DiagnosticsLogger`)

- **Ring Buffer:** Fixed-size in-memory ring buffer (200 entries).
- **Redaction Rules:**
  - 32-character and 64-character hex strings (potential local keys or tokens) are masked as `[REDACTED_32_HEX]` / `[REDACTED_64_HEX]`.
  - Passwords, access tokens, and MAC addresses matching sensitive patterns are stripped before export.
  - Zero partial reveal: keys are not partially displayed (e.g. `1234****abcd` is prohibited).
