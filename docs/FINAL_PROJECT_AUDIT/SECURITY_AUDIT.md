# SECURITY AND RELIABILITY AUDIT

This document evaluates the security architecture, data storage, network configurations, credential protection, and reliability aspects of the **Room AC Controller** project.

---

## 1. Credential & Key Storage (`EncryptedConfigStorage.kt`)

### 1.1 Implementation Architecture
- **Storage Target**: Private `SharedPreferences` (`"room_ac_secure_prefs"`), created with `Context.MODE_PRIVATE`.
- **Encryption Algorithm**: AES-256-GCM (`"AES/GCM/NoPadding"`) authenticated encryption.
- **Hardware Key Management**: Uses `AndroidKeyStore` provider with key alias `"RoomAcKeystoreKey"`.
  - Keys are generated inside the Android hardware-backed Keystore / Secure Element (when supported by physical device).
  - Initialization uses a 12-byte random IV prepended to the ciphertext.
- **JVM Fallback**: Unit tests running off-device on the host JVM fall back safely without crashing `AndroidKeyStore`.

### 1.2 Evaluation & Security Strengths
- **Strengths**: 
  - The local AES key (`localKeyHex`) used by the HaiSmart driver is never written in plaintext to disk.
  - Backup is explicitly disabled in `AndroidManifest.xml` (`android:allowBackup="false"`), preventing extraction via `adb backup`.
- **Weakness / Consideration**: If an attacker gains root access to an unlocked physical phone, any app running with root privileges can inspect process memory, but storage at rest complies with Android security best practices.

---

## 2. Network Security & AndroidManifest Analysis

### 2.1 Manifest Declarations (`AndroidManifest.xml`)
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```
- **Permission Scope**:
  - `INTERNET` and `ACCESS_NETWORK_STATE` are standard for network socket communication.
  - No dangerous/runtime permissions (e.g. `ACCESS_FINE_LOCATION`, Camera, Microphone, Storage) are requested.
- **Cleartext Traffic Setting**:
  - `android:usesCleartextTraffic="true"` is declared.
  - **Reason**: Local TCP communication with legacy IoT devices (such as Haier ACs on port 56800) uses raw TCP sockets without TLS/HTTPS. Enabling this is required for unencrypted local TCP sockets on Android 9+ (API 28+).

---

## 3. Sensitive Data Redaction in Logging (`DiagnosticsLogger.kt`)

### 3.1 Redaction Implementation
- `DiagnosticsLogger` intercepts every log message and applies automated regex sanitization:
  - **Password/Token Redaction**: `Regex("(?i)\\b(password|token|key|secret)\\s*=\\s*([\"']?[^\\s,\"']+[\"']?)")` converts values to `[REDACTED]`.
  - **Hex Key Redaction**: `Regex("(?i)\\b([0-9a-f]{32}|[0-9a-f]{64})\\b")` masks 16-byte (32 hex char) and 32-byte (64 hex char) keys, preserving only the first 4 and last 4 characters for debugging (e.g. `a1b2...[REDACTED]...ef01`).
- **Memory Footprint**: Capped to an in-memory ring buffer of 200 entries to prevent memory leaks.
- **Exporting**: Export button copies sanitized logs to clipboard without exposing pre-shared AES keys.

---

## 4. Reliability & Fault Tolerance

### 4.1 Connection Management
- Socket connections set explicit connect timeouts (`3000ms`) and socket read timeouts (`sock.soTimeout = 4000ms`).
- Coroutine dispatch uses `Dispatchers.IO` to ensure the Android main UI thread is never blocked during network I/O.
- Disconnect routine cleanly closes active `Socket`, `InputStream`, `OutputStream`, and cancels listening coroutines.

### 4.2 State Integrity & Handling of Unconfirmed Commands
- If a socket command succeeds in transmission but the AC fails to return a response packet before the read timeout:
  - The driver **does not crash**.
  - It does not blindly claim confirmed success.
  - It returns `CommandResult.SentUnconfirmed`, warning the user that the command was sent but confirmation was not received.

---

## 5. Potential Security & Reliability Risks

1. **Unencrypted Local Wi-Fi Traffic on SmartAir2**: The SmartAir2 protocol is unencrypted by hardware design. Anyone on the same local Wi-Fi network who sniffs packets can see the AC's state and commands.
2. **Wi-Fi Network Isolation**: If the user's home Wi-Fi uses "AP Isolation" or "Guest Mode" where client devices cannot talk to each other, the app will not be able to connect to the AC's IP.
3. **Dynamic IP Changes**: If the AC's IP changes via DHCP, the app will fail to reach it until the new IP is entered in Setup. (A static DHCP lease on the home router is recommended).

