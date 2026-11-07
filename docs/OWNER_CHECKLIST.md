# Owner Information Checklist (Phase 0)

Please complete the following checklist and provide the requested information, photos, and test results. These details are required before proceeding to Phase 1 (Connectivity Investigation).

---

## 1. Hardware & Physical Unit Information

- [ ] **1.1 Indoor Unit Rating Label Photo / Text**
  - Brand / Manufacturer: 
  - Model number:
  - Serial number / Specs (if visible):
  - *Photo location / filename:*
- [ ] **1.2 Outdoor Unit Rating Label Photo / Text** (if safely accessible)
  - Model number:
  - *Photo location / filename:*
- [ ] **1.3 Original Remote Control Photo**
  - Remote model number (usually printed on front or back):
  - *Photo location / filename (front & back):*
- [ ] **1.4 Wi-Fi Module Hardware**
  - Is the Wi-Fi module built-in or a plug-in USB/dongle stick?
  - Any model, FCC ID, MAC address, or serial on the Wi-Fi module/stick?
  - *Photo location / filename (if visible/accessible):*

---

## 2. Official Manufacturer App Details

- [ ] **2.1 App Identification**
  - Exact App Name:
  - App Publisher (Developer name on Google Play):
  - Google Play Store Link:
  - App Version installed on your phone:
- [ ] **2.2 App Screenshots**
  - Main dashboard / device listing screen
  - Full AC control screen (showing all buttons/modes)
  - Device info / settings screen (showing firmware version, MAC address, IP address if displayed)
  - *Screenshot location / filenames:*

---

## 3. Physical Capabilities & Controls

- [ ] **3.1 Heating Support**
  - Does this real physical unit support Heating (Heat mode)? [Yes / No / Unsure]
  - Does the remote have a "Heat" button/symbol?
  - Does the official app show a "Heat" mode?
- [ ] **3.2 Temperature Range & Step**
  - Lowest target temperature allowed: (e.g., 16°C)
  - Highest target temperature allowed: (e.g., 30°C)
  - Temperature unit: [°C / °F]
  - Step per press: [0.5° or 1.0°]
- [ ] **3.3 Fan Movement (Swing)**
  - What swing options exist on the remote? (e.g., Vertical Swing ON/OFF, Fixed positions 1-5, Horizontal Swing)
  - What swing options exist in the official app?

---

## 4. Network & Phone Environment

- [ ] **4.1 Router / Connected Devices List**
  - Does the AC appear in your home Wi-Fi router's client/device list? [Yes / No]
  - Hostname / Device name displayed in router:
  - IP Address assigned to AC (e.g., 192.168.x.x):
  - MAC Address of AC:
- [ ] **4.2 Test Phone Specs**
  - Phone Make & Model:
  - Android Version:
  - Does the phone have a built-in IR (infrared) blaster? [Yes / No / Unsure]

---

## 5. Physical AC & Network Behavior Tests

Please run these three brief tests and record the outcome:

- [ ] **5.1 Test A: Offline Local Control Test**
  - *Instructions:* Keep phone connected to home Wi-Fi. Turn mobile data OFF. Disconnect router's internet uplink (WAN/DSL cable unplugged, or disable internet access for AC/phone, while leaving local Wi-Fi active). Try controlling the AC via the official app.
  - *Result:* [Works immediately / Works after delay / Does not work / App gives offline error]
  - *Notes:*

- [ ] **5.2 Test B: Bi-directional State Sync Test**
  - *Instructions:* 
    1. Turn AC ON or change temperature using physical remote. Does the official app update its displayed state? If yes, how long does it take (seconds)?
    2. Change temperature in the official app. Does the remote (or AC beep/display) update immediately?
  - *Result / Latency:*

- [ ] **5.3 Test C: Control Latency Test**
  - *Instructions:* With regular home internet working, tap Power or Temperature in the official app.
  - *Result:* Roughly how long (in seconds or instant) from tap until the physical AC beeps/responds?

---

## 6. Preferences & Constraints

- [ ] **6.1 Remote / Away-from-home Control**
  - Is control required when outside home Wi-Fi (over mobile data / remote network), or is local home Wi-Fi only acceptable? [Home Wi-Fi only / Away-from-home required]
- [ ] **6.2 Manufacturer Account Login**
  - Is it acceptable for our app to securely prompt for and store your official manufacturer app account credentials (tokens/password in protected device storage) if cloud API is required? [Yes / No]
- [ ] **6.3 Co-existence**
  - Will the official manufacturer app remain installed on your phone alongside our custom app? [Yes / No]

