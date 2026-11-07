# FINAL HARDWARE SETUP RUNBOOK — ROOM AC CONTROLLER

> **Context:** All application code, domain logic, protocol drivers, automated tests, and APKs have been built autonomously while you were away from the AC.
> This runbook is your definitive guide for "Hardware Day" (in ~2 days) when you return to the physical AC.

---

## 1. Current State of the Codebase

| Feature / Component | Status | Verification Status | Notes |
|---|---|---|---|
| **1. Power (ON / OFF)** | ✅ Implemented & Unit-Tested | ⚠️ Not verified on real AC | Wired to `SmartAir2Driver` & `HaiSmartDriver` |
| **2. Mode (Cool / Heat)** | ✅ Implemented & Unit-Tested | ⚠️ Not verified on real AC | Heating chip conditionally rendered if supported |
| **3. Temperature (+ / - / Display)** | ✅ Implemented & Unit-Tested | ⚠️ Not verified on real AC | 350ms debounce; 0.5°/1.0° steps supported |
| **4. Fan Swing / Movement** | ✅ Implemented & Unit-Tested | ⚠️ Not verified on real AC | Vertical & horizontal louvers supported by protocol |
| **SmartAir2 Driver** | ✅ Implemented & Unit-Tested | 🔬 `UNVERIFIED_FROM_DOCUMENTATION` | Local TCP port 56800, binary framing (0xFF 0xFF) |
| **HaiSmart Driver** | ✅ Implemented & Unit-Tested | 🔬 `UNVERIFIED_FROM_DOCUMENTATION` | Local TCP port 56800, AES-128-CBC framing (0x55 0xAA) |
| **hOn Driver** | ⚠️ Explicit Stub | ⚠️ `STUB_MISSING_INFO` | Cloud-only protocol; returns safe error |
| **In-App Simulator** | ✅ Debug-only | — | Excluded from release APK builds |

---

## 2. What Is Still Unknown & What to Check

When you are back with the physical AC, check these three physical items:

1. **Wi-Fi Module on the AC:**
   - Look inside or at the side/bottom sticker of the indoor unit.
   - Is there a USB Wi-Fi dongle (e.g. KFR-xx stick), or is the Wi-Fi board built into the chassis?
   - Note down any model code on the module (e.g. `SmartAir2`, `ESP-WROOM`, `HaiSmart`).
2. **AC Provisioning State:**
   - Has the AC ever been connected to your 2.4 GHz home Wi-Fi network?
   - If not, use the original remote (or standard Haier AP mode) to pair it to your home Wi-Fi router.
3. **Local Encryption Key (if HaiSmart module):**
   - The HaiSmart protocol requires a 16-byte local key (32 hex characters).
   - If using SmartAir2, **no key is required** (it uses unencrypted binary frames).

---

## 3. Step-by-Step Hardware Day Runbook

### Step 1: Connect AC to Home Wi-Fi
1. Turn ON the wall breaker/power to the Haier AC.
2. If the AC is not already connected to your home Wi-Fi:
   - On the original remote, press the `HEALTH` or `AIRFLOW` button 8 times (or hold the `Wi-Fi` button for 5 seconds) until the AC beeps twice and the display shows `CF` or `AP`.
   - On your phone, connect to the AC's temporary setup Wi-Fi network (usually named `Haier-XXXX` or `U-AC-XXXX`).
   - Use the standard setup tool or router WPS to assign your home 2.4 GHz Wi-Fi SSID and password to the AC.
3. Once joined, confirm the AC's Wi-Fi LED is solid.

### Step 2: Find the AC's Local IP Address
1. Open your home router admin page (e.g. `http://192.168.1.1` or `http://192.168.100.1`).
2. Look at the DHCP Client List for a device with hostname containing `Haier`, `Espressif`, or `SmartAir`.
3. Note the IP address (e.g. `192.168.1.105`).
4. *(Recommended)* Set a DHCP reservation so this IP does not change.

### Step 3: Install the App on Your Android Phone
1. Connect your phone via USB or copy `dist/room-ac-controller-v1.0.0-debug.apk` to your phone.
2. Install the APK (allow installation from unknown sources).

### Step 4: Configure & Test the Connection
1. Open **Room AC Controller**.
2. Tap **Set Up Connection**.
3. **Protocol Selection:**
   - **First try:** Select **Haier SmartAir2 (Binary / Local Port 56800)**.
   - Enter your AC's IP address. Port defaults to `56800`.
4. Tap **Test Connection**.
   - If successful: You'll see a green checkmark! Tap **Save & Apply**.
   - If it fails (connection refused or timeout): Switch to **Haier HaiSmart (U+ / AES-128 / Port 56800)**.
     *(Note: HaiSmart requires entering the 16-byte localKeyHex)*.

### Step 5: Verify the 4 Core Features Against Real AC
Test each feature one by one and physically observe the AC unit:

1. **Power:**
   - Tap the central Power button in the app.
   - *Physical check:* Does the AC emit a beep and open its louvers / start the compressor?
   - Tap Power again to turn it OFF.
2. **Mode:**
   - Turn the AC ON.
   - Tap **Cooling**. Check that the AC display shows the snowflake icon.
   - Tap **Heating** (if supported). Check that the AC display shows the sun icon.
3. **Temperature:**
   - Tap `+` and `−`.
   - Observe that rapid taps are coalesced smoothly (350ms debounce).
   - *Physical check:* Does the AC unit beep and update its target temperature display?
4. **Fan Swing:**
   - Select `Auto` or `Top`/`Bottom`.
   - *Physical check:* Do the motorized horizontal/vertical louvers swing or hold the position?

### Step 6: Save Physical Capabilities
In the app Setup screen:
- Turn ON **Override AC Capabilities**.
- Enter the discovered min temp, max temp, step size, and whether heating is supported.
- Tap **Save & Apply**.

---

## 4. Fallback / Escalation Decision Tree

```
[Start Test Connection]
         │
         ▼
Does port 56800 connect?
   ├── YES ──► Does it accept SmartAir2 0xFF 0xFF packet?
   │              ├── YES ──► CONGRATULATIONS! SmartAir2 driver works.
   │              └── NO  ──► Try HaiSmart with AES key.
   │
   └── NO  ──► Run port scan from PC on same Wi-Fi:
                 Test-NetConnection -ComputerName <AC_IP> -Port 56800
                 Test-NetConnection -ComputerName <AC_IP> -Port 6800
                 Test-NetConnection -ComputerName <AC_IP> -Port 80
                 (If ports differ, update Port in Setup screen).
```
