# HARDWARE TEST LOG — ROOM AC CONTROLLER

This log documents physical testing performed against the real Haier Smart AC unit.
**Rule:** A test is marked `PASS` only when the real, physical AC responds and state verification is observed.

---

## Hardware Day Verification Log

**Test Date:** *(Pending Hardware Day)*  
**Tester / Owner:** Hex Ali  
**AC Model:** *(To be filled from physical unit sticker)*  
**Wi-Fi Module Type:** *(SmartAir2 / HaiSmart / Built-in)*  
**Assigned Local IP:** *(e.g. 192.168.1.105)*  
**Selected Driver:** *(Haier SmartAir2 / Haier HaiSmart)*  

---

### Test Matrix

| # | Feature / Action | Test Procedure | Expected Hardware Result | Observed Result | Status | Notes / Evidence |
|---|---|---|---|---|---|---|
| **1** | **Connection Test** | Enter AC IP & Port in Setup, tap *Test Connection* | Successful TCP handshake on port 56800 | | ⏳ PENDING | |
| **2** | **Power ON** | From OFF state, tap central Power button | AC beeps, louvers open, compressor begins starting | | ⏳ PENDING | |
| **3** | **Power OFF** | From ON state, tap central Power button | AC beeps, louvers close, compressor shuts down | | ⏳ PENDING | |
| **4** | **Mode: Cooling** | Tap *Cooling* chip | AC switches to cool mode; snowflake symbol on AC | | ⏳ PENDING | |
| **5** | **Mode: Heating** | Tap *Heating* chip | AC switches to heat mode; sun symbol on AC (if supported) | | ⏳ PENDING | |
| **6** | **Temperature UP** | Tap `+` button | Target temp increases on physical display; beep heard | | ⏳ PENDING | |
| **7** | **Temperature DOWN** | Tap `−` button | Target temp decreases on physical display; beep heard | | ⏳ PENDING | |
| **8** | **Rapid Temperature Tap** | Rapidly tap `+` three times in 1 second | Coalesced into a single network packet after 350ms | | ⏳ PENDING | Verified by unit tests; observe physical beep count |
| **9** | **Vertical Swing** | Select *Auto* / *Top* / *Bottom* | Motorized vertical louver moves to matching position | | ⏳ PENDING | |
| **10**| **Horizontal Swing**| Select *Auto* (if supported) | Internal motorized horizontal vanes move | | ⏳ PENDING | |
| **11**| **Unconfirmed State** | Send command with AC temporarily disconnected | App displays "sent, not confirmed" or timeout banner | | ⏳ PENDING | |
| **12**| **Diagnostics Redaction**| Inspect diagnostics log in app | Local key is masked as `[REDACTED]` | | ⏳ PENDING | Verified in unit test |

