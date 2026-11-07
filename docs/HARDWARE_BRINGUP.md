# Physical Hardware Bring-Up & Testing Guide

This document is the actionable checklist for when the Android device running "Room AC Controller" is co-located on the same local Wi-Fi network as the physical Haier Smart AC.

---

## 1. Network Preparation & Safety Precautions

1. **Dedicated Subnet**:
   - Ensure the Android phone and the Haier AC Wi-Fi module are connected to the same 2.4 GHz Wi-Fi SSID / subnet (e.g., `192.168.1.0/24`).
   - Disable AP/Client Isolation on the Wi-Fi router.
2. **Safety Gate Verification**:
   - The app's **Safety Gate** will not transmit temperature, mode, or swing adjustments until the current power state is either:
     a) Read back and confirmed from the unit (`CONFIRMED_BY_AC`), OR
     b) Explicitly asserted in Setup via **Owner-Asserted Baseline** (`OWNER_ASSERTED`).
3. **Physical Remote on Hand**:
   - Keep the original infrared remote control near you during testing to verify physical state changes and to restore the AC to a known state if necessary.

---

## 2. Step-by-Step Bring-Up Procedure

### Step 1: Discover AC IP Address
- Inspect your Wi-Fi router's DHCP client table for hostnames matching `HAIER`, `SmartAir`, or MAC OUI `00:07:A8` / Espressif OUIs.
- Note the assigned IP address (e.g. `192.168.1.105`).

### Step 2: Run Passive Connection Probe (Non-Destructive)
1. Open the app and tap **Settings** (gear icon) to open **SetupScreen**.
2. Select **SmartAir2** driver.
3. Enter the AC's IP address. Set Port to `56800`.
4. Tap **"Probe AC (Passive)"**:
   - This opens a TCP connection and waits passively for 3000ms without sending any control frames.
   - If the module sends a periodic beacon/status banner on connect, the hex output will be displayed in the green banner.
   - If connection succeeds but the AC remains silent, the banner will report "Connected, but AC remained silent".
   - If connection is refused, verify port `56800` or check router firewall settings.

### Step 3: Test Power Control (SmartAir2 First)
1. Enable **Owner-Asserted Baseline** in Setup:
   - Match the settings on your physical remote (e.g. `Power = OFF`, `Mode = COOL`, `Temp = 24°C`).
   - Tap **"Save & Apply"**.
2. On the main screen, tap the **Power Button**:
   - Listen for the physical beep from the indoor unit.
   - Observe the UI state badge: `Optimistic` while transmitting, transitioning to `Confirmed` once status response arrives.
3. If no beep is heard:
   - Open Setup > Diagnostics Log.
   - Copy the log (it contains redacted timestamps and raw TX/RX frames).
   - Check if response packets were received.

### Step 4: Test HaiSmart Protocol (If SmartAir2 fails)
1. If the unit does not respond to SmartAir2 frames or requires encryption:
   - Obtain the 16-byte local key (via packet capture during official app pairing or token extraction).
   - Select **HaiSmart** driver in Setup.
   - Enter the 32-character hexadecimal key.
   - Test Power ON/OFF.

---

## 3. Wireshark / Packet Capture Reference
To capture packets between phone and AC for validation:
```bash
# Filter on AC IP
tcp.port == 56800 and ip.addr == <AC_IP>
```
Look for:
- Frame magic `0xFF 0xFF` for SmartAir2.
- Frame magic `0x55 0xAA` for HaiSmart.

