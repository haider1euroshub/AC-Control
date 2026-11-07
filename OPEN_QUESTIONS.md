# Open Questions & Unresolved Unknowns

*Status as of Phase 0 initiation (2026-11-07)*

This living document tracks all active unknowns and open questions. No capability, endpoint, protocol, or hardware claim will be assumed until verified by the owner or established by direct investigation evidence.

---

## 1. Hardware & Device Identification
- **OQ-1.1:** What is the exact manufacturer and model number of the indoor unit?
- **OQ-1.2:** What is the model number of the outdoor unit?
- **OQ-1.3:** What is the model number of the original remote controller?
- **OQ-1.4:** Is the Wi-Fi module built into the AC motherboard or a plug-in USB/dongle stick? What are its chipset / MAC / identifier details?

## 2. Official App & Cloud Ecosystem
- **OQ-2.1:** What is the exact official app name, publisher, version, and Play Store link used to control this AC?
- **OQ-2.2:** What cloud ecosystem or IoT platform does the app run on (e.g., Tuya/Smart Life, Haier Smart Air / hOn, Midea MSmartHome, Gree+, Broadlink, etc.)?

## 3. Physical AC Capabilities & Constraints
- **OQ-3.1:** Does this unit physically support Heating mode, or is it Cooling-only?
- **OQ-3.2:** What is the minimum and maximum target temperature range (e.g. 16°C – 30°C)?
- **OQ-3.3:** What is the temperature adjustment step size (0.5°C or 1.0°C) and unit (°C or °F)?
- **OQ-3.4:** What exact swing modes are supported (Vertical swing toggle vs discrete positions; Horizontal swing)?

## 4. Connectivity, Protocols & Telemetry
- **OQ-4.1:** Does the AC maintain an open local port on the LAN (e.g. UDP broadcast/TCP port), or is it strictly cloud-dependent (MQTT, HTTPS, WebSocket to manufacturer servers)?
- **OQ-4.2:** Does local LAN control work when the internet WAN connection is disconnected (Test A outcome)?
- **OQ-4.3:** Does the AC report its state back to the app (bidirectional feedback), or is state fire-and-forget?
- **OQ-4.4:** What is the baseline command latency from user interaction to AC beep/response?

## 5. User Scope & Requirements
- **OQ-5.1:** Is out-of-home control strictly needed, or is LAN-only control acceptable if local control is supported?
- **OQ-5.2:** Does the owner permit logging into their manufacturer account from our app if cloud communication is required?
- **OQ-5.3:** Does the owner's Android phone have an IR emitter (for IR fallback option if Wi-Fi fails)?

