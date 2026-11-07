# Local Wi-Fi Control Findings

*Last updated: 2026-11-07*  
*Author: Lead Engineer (Autonomous Agent)*

---

## 1. Executive Summary

| Parameter | Finding | Confidence / Status |
|---|---|---|
| **App Family** | Haismart (Haier U+ / SE-Asia) or SmartAir2 | High probability Haismart (`com.haier.uhome.uplus.seasia`); needs confirmation of exact app name |
| **Local Port** | TCP 56800 | Industry standard across all verified Haier local Wi-Fi integrations (`haismart-local`, `haier-ac-remote`, `HaierACBridge`) |
| **Transport** | Persistent TCP Socket | Direct connection to AC IP on LAN port 56800 |
| **Telemetry / State** | Local Push / State Broadcast | The appliance periodically pushes full state packets over the established TCP socket |
| **Authentication & Encryption** | AES-128 (Haismart) or Plain binary frame (SmartAir2) | If Haismart: requires a 16-byte `localKey` (retrieved once via cloud pairing API and stored locally). If SmartAir2: unencrypted MAC-bound frame. |
| **Cloud Independence** | Fully local after initial key acquisition (if Haismart) or 100% local (if SmartAir2) | Direct LAN packets bypass Haier servers once paired |

---

## 2. Protocol Families & Technical Architecture

### 2.1 Family A: Haismart (Haier U+ / uHome SE-Asia)
- **App Package:** `com.haier.uhome.uplus.seasia` (Official Haier app for Pakistan & SE Asia).
- **Transport:** TCP to port `56800`.
- **Encryption:** AES-128 encryption with a per-device `localKey`.
- **Key Origin:** The official app authenticates with Haier cloud (`gw.haieriot.com` / U+ IoT gateway) during onboarding to bind the device and receives the device's `localKey`.
- **Local Autonomy:** Once the `localKey` is held, all control commands and state reports occur directly between the client and the AC on the LAN without internet connectivity.
- **State Readback:** The AC pushes state updates over the open TCP socket every few seconds and immediately following valid commands.

### 2.2 Family B: SmartAir2 (Legacy USB Dongles)
- **App Package:** `com.haier.smartair2`
- **Transport:** TCP port `56800`.
- **Encryption:** None (raw binary frames containing command IDs, target temperatures, mode flags, and CRC/checksums).
- **Key Origin:** No cryptographic key required; addresses device by IP and MAC address.

---

## 3. Four Core Capabilities Status Matrix

As required by Section 4.5 of the Master Prompt, status must distinguish between implemented, unit tested, and verified on real AC.

| Feature | Local Command Support | Status | Evidence / Notes |
|---|---|---|---|
| **1. Power ON / OFF** | Supported over TCP 56800 | **Not verified on real AC** | Awaiting network discovery confirmation and protocol binding to target AC. |
| **2. Mode: Heating / Cooling** | Supported over TCP 56800 | **Not verified on real AC** | AC capability packet will declare whether heat pump mode is physically present. |
| **3. Temperature (Up/Down/Display)** | Supported over TCP 56800 | **Not verified on real AC** | Step size (0.5°C vs 1.0°C) and limits (16°C–30°C) to be queried from device telemetry. |
| **4. Fan Swing / Movement** | Supported over TCP 56800 | **Not verified on real AC** | Supports vertical/horizontal axis depending on physical hardware capabilities. |

---

## 4. Local Network Discovery & Port Check Results

- **Host Interface:** Wi-Fi `192.168.100.29` (Subnet: `192.168.100.0/24`, Gateway: `192.168.100.1`).
- **Active Probe Status:** Subnet sweep for TCP port 56800 in progress via `investigation/scan_ac.ps1`.

