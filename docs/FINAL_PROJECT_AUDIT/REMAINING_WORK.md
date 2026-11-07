# REMAINING WORK & HARDWARE-DAY READINESS

This document catalogs all remaining tasks required to achieve successful physical control of the Haier Smart Wi-Fi Air Conditioner.

---

## 1. Prioritized Task Categorization

### Category A: Work That Can Be Completed Without the Physical AC
- [x] **Debug APK Build**: Complete (`app/build/outputs/apk/debug/app-debug.apk` exists, 15.73 MB).
- [x] **JVM Unit Tests**: 100% passing across all 6 modules.
- [x] **Contract Test Isolation**: `DriverContractTest` moved to `testFixtures` to prevent DEX naming clashes.
- [ ] **Release APK Compilation**: Run `./gradlew.bat :app:assembleRelease` if an unaligned signed release artifact is desired (debug APK is already fully installable via adb/side-load).
- [ ] **Physical Android Phone Side-loading**: Transfer `app-debug.apk` to an Android phone (Android 8.0+ / API 26+) and verify app launches, views render smoothly, and settings screens are accessible.

---

### Category B: Work Requiring Access to the Physical AC
- [ ] **Wi-Fi Module Identification**: Inspect the physical AC indoor unit sticker, USB Wi-Fi dongle, or maintenance panel to confirm the Wi-Fi module model (e.g. KFR-xxx, SmartAir2, or HaiSmart).
- [ ] **AC Local IP Assignment**: Verify the AC is joined to the same 2.4 GHz Wi-Fi SSID as the Android phone. Identify its local IPv4 address (e.g. via home router DHCP client list or router admin panel).
- [ ] **Port 56800 Reachability Test**: Open the app's Setup screen, enter the AC's local IP, port `56800`, and tap **"Test Connection"**.
- [ ] **Protocol Driver Selection**:
  - If unencrypted port 56800 handshake succeeds -> Select **Haier SmartAir2**.
  - If port opens but packets are rejected or U+ encryption is active -> Select **Haier HaiSmart** and input the 16-byte local key.
- [ ] **Physical Execution of Test Matrix**: Follow [`docs/TEST_LOG.md`](file:///c:/Users/Hex%20Ali/Desktop/AC%20Control/docs/TEST_LOG.md) to test:
  1. Power ON / OFF (listen for hardware beep, observe louver motor).
  2. Mode switching between Cool and Heat.
  3. Temperature increment/decrement (verify physical indoor unit seven-segment display changes).
  4. Vertical swing louver movement.

---

### Category C: Information Required from Owner on Hardware Day
1. **The exact model number of the Haier AC** (printed on the indoor unit barcode sticker).
2. **The official mobile app previously used to set up the AC** (e.g. *Haier SmartAir2*, *HaiSmart*, or *hOn*).
3. **The local IP address of the AC** on your home router (e.g. `192.168.1.xxx`).
4. **The 16-byte local key (if using HaiSmart)**: If your unit runs on the HaiSmart platform, the 16-byte key must be extracted from the HaiSmart app configuration or app backup.

---

### Category D: Potential Blockers to Local Wi-Fi Control
1. **Cloud-Locked Firmware (hOn Ecosystem)**: If the AC is a newer model that only operates with the Haier *hOn* app, its Wi-Fi module will reject unauthenticated local TCP connections on port 56800 and only communicate with Haier cloud servers via TLS/MQTT.
2. **Router Client Isolation**: If your home Wi-Fi router has "AP Isolation" or "Guest Mode" enabled, the phone will be blocked from sending TCP packets directly to the AC's IP address.
3. **Missing Local Encryption Key**: For HaiSmart units, commands cannot be encrypted without the pre-shared 16-byte key.

---

## 2. Hardware Testing Day Step-by-Step Playbook

```
Step 1: Install app-debug.apk on your Android phone.
Step 2: Connect your phone to your home Wi-Fi (2.4 GHz network).
Step 3: Open your Wi-Fi router admin page, find the AC in the connected device list, and record its IP.
Step 4: Launch "Room AC Controller" on your phone.
Step 5: Tap "Configure Device" -> enter AC IP and port 56800.
Step 6: Select "Haier SmartAir2" -> tap "Test Connection".
        ├── IF SUCCESS: Tap "Save Configuration" and test Power/Temperature on the Main screen!
        └── IF FAILED / REFUSED:
            ├── Try "Haier HaiSmart" with your 16-byte key.
            └── If port 56800 is closed, the unit may be an hOn cloud-locked model.
```

