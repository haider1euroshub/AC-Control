# Protocol Verification Status Matrix

> **ABSOLUTE TRUTHFULNESS NOTICE**:
> None of the protocol drivers or packet payloads documented below have been tested against or verified on physical Haier AC hardware.
> All implementations are based on open-source community reverse-engineering references.
> They are strictly classified under evidence levels as defined below.

---

## Evidence Level Definitions

- **VERIFIED_ON_REAL_AC**: Confirmed by direct hardware packet traces and physical command execution on this specific AC unit. (*Count: 0*)
- **PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC**: Derived from public community repositories (ESPHome, bstuff, fastfend), but unconfirmed on this unit's firmware/module.
- **ASSUMED**: Inferred default values or defensive safeguards created in the absence of authoritative hardware documentation.

---

## Detailed Protocol Matrix

| Protocol Driver | Module | Framing / Port | Cryptography | Power Control | Mode Control | Temp Control | Swing Control | Hardware Verification Status |
|---|---|---|---|---|---|---|---|---|
| **SmartAir2** | `:driver-smartair2` | TCP `56800`<br>`0xFF 0xFF` framing | Plaintext / Checksum byte (sum mod 256) | `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC` (Offset 10 / 8) | `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC` (Offset 14 / 12) | `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC` (Offset 12 / 13) | `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC` (Vertical: Off/Oscillate) | **UNVERIFIED_FROM_DOCUMENTATION** |
| **HaiSmart** | `:driver-haismart` | TCP `56800`<br>`0x55 0xAA` framing | AES-128-CBC (PKCS7, static/custom IV) | `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC` (Payload offset 8) | `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC` (Payload offset 10) | `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC` (Payload offset 9) | `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC` (Vertical: Off/Oscillate) | **UNVERIFIED_FROM_DOCUMENTATION** |
| **hOn** | `:driver-hon` | Cloud / BLE / Local HTTPS | TLS / Cloud Session Key | `STUB` | `STUB` | `STUB` | `STUB` | **STUB_MISSING_INFO** (Blocked from LAN transmission) |

---

## Field-by-Field Breakdown

### 1. SmartAir2 Protocol
- **Discovery**: UDP broadcast port 56800 (`PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC`).
- **Packet Magic**: `0xFF 0xFF` (`PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC`).
- **Control Packet Length**: 36 bytes (`PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC`).
- **Status Response Length**: 37 bytes (`PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC`).
- **Power Byte**:
  - Outgoing command: byte index 10 (`0x01` = ON, `0x00` = OFF)
  - Incoming status: byte index 8
- **Mode Byte**:
  - Outgoing command: byte index 14 (`0x00` = COOL, `0x04` = HEAT)
  - Incoming status: byte index 12
- **Temperature Byte**:
  - Outgoing command: byte index 12 (Target temp: value - 16)
  - Incoming status: byte index 13 (Target temp: value + 16)
  - Indoor temperature: byte index 26 (`PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC`)
- **Swing Byte**:
  - Outgoing command: byte index 11 (Vertical: `0x00` = OFF, `0x01` = AUTO/OSCILLATE)
  - Incoming status: byte index 9

### 2. HaiSmart Protocol
- **Packet Magic**: `0x55 0xAA` (`PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC`).
- **Framing**: `[0x55, 0xAA, Len_MSB, Len_LSB, Encrypted_Payload..., Checksum]`.
- **Encryption**: AES-128-CBC (`javax.crypto.Cipher`), 16-byte hex key required.
- **Fail-Safe Mechanism**: Zero-key fallback rejected; payload verification requires non-empty user-supplied key.

### 3. hOn Protocol
- **LAN Status**: No confirmed local-only unauthenticated port exists.
- **Current App Behavior**: Driver returns `AcError.ProtocolNotVerified("hOn")`, safely blocking transmission until hardware capture or valid auth parameters are provided.

