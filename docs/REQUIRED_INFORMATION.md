# Required Information & Decisions

*Living document for user inputs and decisions. Updated autonomously as investigations proceed.*

---

## Section 1. Information I Absolutely Must Provide

Please answer the single item below. This is the only information currently required from you.

### Item 1.1: Official App Name & Source
- **What is needed:** The exact name of the official app installed on your phone that currently controls your Haier AC, and whether you installed it from the Google Play Store (e.g., "Haismart from Play Store", "Haier Smart from Play Store", or "SmartAir2").
- **Why it is necessary:** Haier has two distinct protocol families in South Asia: **Haismart** (which uses AES-128 encryption with a device key over TCP port 56800) and **SmartAir2** (which uses unencrypted binary frames over TCP port 56800). The app name immediately establishes the correct protocol family without invasive reverse-engineering.
- **How you can get it:** Long-press the app icon on your phone -> Tap **App info** (the small "i" icon) -> Read the app title displayed at the top.
- **Mandatory, or can it be investigated another way?** Can theoretically be probed by attempting both handshake variants over the local network, but asking for the app title takes 5 seconds, eliminates all ambiguity, and prevents sending incompatible handshake frames to your physical unit.

---

## Section 2. Things I (The Agent) Will Investigate Myself

*Transparency list — no action required from you for any of these:*
1. **AC Local IP & MAC Address:** Scanning the local `192.168.100.0/24` subnet for the AC's listener on TCP port 56800.
2. **Heating Support:** Querying the device's capability telemetry over the socket to check if heat pump mode is supported by your physical unit.
3. **Temperature Limits & Step:** Reading the target temperature range (min/max) and step (0.5°C or 1.0°C) directly from the AC's telemetry payload.
4. **Swing Flap Axes:** Reading supported horizontal/vertical louvre states from the AC's capability report.
5. **Bidirectional State Sync:** Monitoring real-time state broadcasts pushed by the AC when changed via the physical remote.
6. **Android Tooling & Build Chain:** Setting up the JDK and Android build tools on Windows 11 to compile the APK.

---

## Section 3. Optional Information (Not Needed Now)

- *None currently.* (All physical photos, sticker serials, and router admin screenshots are waived unless the automated network probe cannot locate the AC).

---

## Section 4. Answers Register

| # | Question | Owner Answer | Date Recorded | Status |
|---|---|---|---|---|
| 1 | Official app name and Play Store source | *Awaiting answer* | — | **PENDING** |

---

## Section 5. Status

- **Blocked on Owner:** Awaiting confirmation of the official app name (Item 1.1).
- **Not Blocked (Active Autonomous Work):**
  - Subnet port 56800 scanning.
  - Setting up Android SDK / Kotlin build pipeline.
  - Preparing the local network communication module.

---

## Section 6. Decisions Needed from Me (Rule 3.1)

*Owner approvals are required only for: (a) spending money/hardware, (b) changing connectivity approach, (c) scope expansion.*

- **Current Approvals Required:** **None.** 
  Local Wi-Fi control remains the active path. No extra hardware, expenditure, or scope expansion is requested.

