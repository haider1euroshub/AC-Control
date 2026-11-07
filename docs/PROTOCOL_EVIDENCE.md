# PROTOCOL EVIDENCE & REVERSE-ENGINEERING CATALOG

**Rule:** Every protocol layout, field offset, command byte, port, and checksum algorithm in this codebase is cataloged below with its source and verification status.  
**Nothing is marked verified on hardware until real packet captures on the physical AC are recorded.**

---

## 1. Haier SmartAir2 Protocol

- **Evidence Label:** `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC`
- **Public Sources:**
  1. `bstuff/haier-ac-remote` (GitHub repository, MIT License, retrieved 2024/2025). Reverse-engineered communication with USB SmartAir2 Wi-Fi modules over TCP port 56800.
  2. `fastfend/HaierACBridge` (GitHub repository, MIT License).
  3. `paveldn/haier-esphome` & `HaierProtocol` library (ESPHome component by Pavel Doikov). Documents the lower-level UART framing between the Wi-Fi module and the AC mainboard (Frame headers `0xFF 0xFF`, length, CRC/checksum).
- **Extracted Details:**
  - Header: `0xFF 0xFF`
  - TCP Port: `56800`
  - Query Frame: `FF FF 00 0A 00 00 00 00 00 01 4D 01 59`
  - Control Frame Payload: 14 bytes + 2 header + 1 checksum = 17 bytes total.
  - Modulo-256 Sum Checksum over bytes `[2 .. size-2]`.
- **Known Divergences / Gaps:**
  - The status response packet structure in some public implementations places status bytes at offsets that do not match the control packet offsets.
  - UART documentation notes that SmartAir2 hardware typically supports only swing ON/OFF (not 5 distinct stepped vertical angles).
  - MAC address is used in certain bstuff discovery flows but omitted in direct TCP socket control.

---

## 2. Haier HaiSmart Protocol (U+ Local)

- **Evidence Label:** `ASSUMED` / `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC`
- **Public Sources:**
  - Reverse engineering of Haier U+ local LAN protocol by community projects (`enapt/haismart-local`).
- **Extracted Details:**
  - Transport: TCP port `56800`.
  - Cryptography: AES-128-CBC with PKCS5/PKCS7 padding.
  - IV: 16 zero bytes (`0x00 * 16`).
  - Key: 16-byte pre-shared local key extracted from user's app database or local pairing.
- **Gaps & Unknowns:**
  - Exact session establishment handshake on LAN without cloud activation is unknown and hardware-dependent.

---

## 3. Haier hOn Protocol

- **Evidence Label:** `PUBLIC_REFERENCE_UNVERIFIED_ON_THIS_AC`
- **Public Sources:**
  - `Andre0512/hon` (Home Assistant integration).
- **Finding:**
  - Modern Haier hOn models communicate with cloud servers via TLS/MQTT (`api-iot.he.services`).
  - Direct local LAN control on port 56800 without cloud credentials is not publicly documented.
  - **Verdict:** Correctly maintained as an honest stub in this project.

