# PROTOCOL DRIVER AUDIT

This document evaluates all protocol driver implementations in the project:
1. **Haier SmartAir2** (`driver-smartair2`)
2. **Haier HaiSmart** (`driver-haismart`)
3. **Haier hOn** (`driver-hon`)

---

## 1. Haier SmartAir2 Driver (`SmartAir2Driver.kt`)

### 1.1 Specification & Protocol Background
- **Protocol Type**: Proprietary binary framing over unencrypted TCP.
- **Default Port**: `56800`.
- **Target Hardware**: Older Haier smart air conditioners equipped with USB Wi-Fi dongles (e.g. KFR-35GW series, early SmartAir2 modules).
- **Supporting Documentation & Reference Implementation**:
  - Reimplemented from open-source reverse-engineering research: `bstuff/haier-ac-remote` (MIT License) and `fastfend/HaierACBridge` (MIT License).
  - Validated packet frame structures against published reverse-engineering teardowns.

### 1.2 Framing & Packet Anatomy
- **Header**: `0xFF 0xFF` (2 bytes).
- **Payload Length**: 2 bytes big-endian.
- **Reserved Subsystem ID**: 4 bytes (`0x00 0x00 0x00 0x00`).
- **Command Type**: 2 bytes (`0x00 0x01` = Query, `0x00 0x0C` = Control).
- **Control Data**:
  - Power: Byte 10 (`0x01` = ON, `0x00` = OFF).
  - Mode: Byte 11 (`0x02` = Cool, `0x04` = Heat).
  - Target Temperature: Byte 12 (`target - 16`).
  - Fan Speed: Byte 13 (Placeholder defaulted).
  - Vertical Swing: Byte 14 (`0x00` = Off, `0x01` = Auto, `0x02`-`0x06` = Pos 1-5).
  - Horizontal Swing: Byte 15 (`0x00` = Off, `0x01` = Auto).
- **Checksum**: Last byte — 8-bit unsigned modulo-256 sum of all bytes from index 2 to end of payload.

### 1.3 Capabilities & Encryption
- **Encryption**: None (plaintext binary).
- **Authentication**: None required.
- **Reported Capabilities**: Temperature range 16°C – 30°C in 1°C steps; Heating supported; Vertical & Horizontal oscillation.

### 1.4 Completeness Assessment
- **Status**: **Fully implemented in code.** Encoder, decoder, checksum validator, socket lifecycle, and contract tests are complete.
- **Verification Level**: `UNVERIFIED_FROM_DOCUMENTATION`. **Not yet verified on real hardware.**

---

## 2. Haier HaiSmart Driver (`HaiSmartDriver.kt`)

### 2.1 Specification & Protocol Background
- **Protocol Type**: Haier U+ local protocol over TCP, encrypted with AES-128-CBC.
- **Default Port**: `56800`.
- **Target Hardware**: Mid-generation Haier Wi-Fi appliances widely sold in South Asia (including Pakistan), Southeast Asia, and the Middle East, configured using the "HaiSmart" Android app.
- **Supporting Documentation & Reference Implementation**:
  - Reimplemented from reverse-engineering work by `enapt/haismart-local` and community Haier U+ research.

### 2.2 Security, Key Requirements & Encryption
- **Encryption Algorithm**: AES-128-CBC with PKCS5Padding.
- **Initialization Vector (IV)**: Default zero IV (`ByteArray(16) { 0x00 }`) as per U+ local framing specs, or parsed from packet preamble.
- **Key Requirement**: A 16-byte (32-character hexadecimal) pre-shared local key.
  - *Where does this key come from?* Extracted from the official HaiSmart app's internal database/backup or captured during local pairing handshake.
  - If no key is entered in the app setup, `HaiSmartDriver.connect()` immediately rejects the connection with `AcError.AuthOrKeyRejected`.

### 2.3 Completeness Assessment
- **Status**: **Fully implemented in code.** `HaiSmartCipher.kt` handles AES-128-CBC encryption/decryption, `HaiSmartPacket.kt` handles packet framing, and `HaiSmartDriver.kt` handles socket connection, polling, and control dispatch.
- **Verification Level**: `UNVERIFIED_FROM_DOCUMENTATION`. **Not yet verified on real hardware.**

---

## 3. Haier hOn Driver (`HonDriver.kt`)

### 3.1 Specification & Protocol Background
- **Protocol Type**: Cloud-first REST / MQTT / TLS protocol.
- **Target Hardware**: Modern European and global Haier appliances released 2021+ that register via the "hOn" app.
- **Supporting Documentation & Reference Implementation**:
  - Researched via `Andre0512/hon` (Home Assistant integration).
  - All communication in the hOn ecosystem routes through Haier's remote cloud API (`https://api-iot.he.services/`).

### 3.2 Local Control Status
- **Local Control Feasibility**: **Cloud-Locked.** There is currently no public reverse-engineered specification for direct, unauthenticated local LAN socket control on hOn Wi-Fi modules without reaching Haier cloud servers.
- **Driver Status**: **Honest Stub.**
  - `verificationStatus = DriverVerificationStatus.STUB_MISSING_INFO`.
  - When invoked, immediately returns `AcError.ProtocolNotVerified("Haier hOn local protocol is undocumented; cloud-free local control is not available")`.
  - Does NOT invent fictitious packets or fake success.

---

## 4. Driver Verification Summary Table

| Driver Module | Protocol Family | Transport | Encryption | Auth / Key Needed | Implementation State | Hardware Status |
|---|---|:---:|:---:|:---:|:---:|:---:|
| `driver-smartair2` | SmartAir2 | TCP 56800 | None | None | **Complete** | ⏳ Unverified on HW |
| `driver-haismart` | HaiSmart (U+) | TCP 56800 | AES-128-CBC | 16-Byte Hex Key | **Complete** | ⏳ Unverified on HW |
| `driver-hon` | hOn | Cloud MQTT | TLS | Cloud Tokens | **Intentional Stub** | ❌ Cloud-Locked |

