# Decisions Log

This document records all significant technical and architectural decisions in plain language, explaining the context, options considered, decision taken, and consequences.

---

## [DEC-001] Strict Scope Boundary: Exactly Four Features
- **Date:** 2026-11-07
- **Status:** Approved / Enforced
- **Context:** Project scope definition for the "Room AC Controller" app.
- **Decision:**
  The app will control the physical unit with strictly four capabilities:
  1. Power ON / OFF
  2. Mode: Heating / Cooling
  3. Temperature: Increase / Decrease / Display Target
  4. Fan Swing / Movement (only what the physical AC supports)
  All extraneous features (fan speed, eco/sleep/turbo/dry modes, timers, schedules, multi-AC, analytics, widgets, user accounts in our app) are strictly barred.
- **Consequences:** Implementation remains lightweight, resilient, and focused entirely on verified hardware control.

---

## [DEC-002] Autonomous Delivery Execution Framework
- **Date:** 2026-11-07
- **Status:** Active
- **Context:** Master Prompt transition from micro-gate approvals to lead engineer autonomous execution.
- **Decision:**
  Approval from the owner is strictly limited to three conditions:
  1. Spending money or buying hardware.
  2. Changing the connectivity approach (e.g. falling back to cloud, IR, or ESP hardware).
  3. Expanding scope beyond the four core features.
  All other engineering choices (architecture, tooling, investigation, protocol decoding, build pipeline) proceed autonomously with transparent documentation.
- **Consequences:** Accelerates delivery while maintaining strict boundaries around costs, scope, and connectivity architecture.

---

## [DEC-003] Default Technology Stack Baseline
- **Date:** 2026-11-07
- **Status:** Active
- **Context:** Mobile application framework and language selection.
- **Decision:**
  Use Kotlin with Jetpack Compose, Material 3, and Gradle on Android (minSdk 26 - Android 8.0 Oreo, targetSdk 34/35). MinSdk 26 ensures broad compatibility across modern Android devices while supporting Java 8+ / Kotlin coroutines and java.time natively without desugaring overhead.
  The local network communication layer will be completely decoupled from the UI layer to allow protocol adjustments without affecting UI components.
- **Consequences:** Modern, maintainable native Android codebase aligned with standard Android architecture guidelines.



---

## [DEC-004] Rapid Temperature Debounce Coalescing (350ms)
- **Date:** 2026-11-07
- **Status:** Approved / Implemented
- **Context:** Users frequently tap temperature `+` or `−` multiple times in quick succession. Sending a TCP packet for every intermediate temperature value can overwhelm low-power microcontroller Wi-Fi modules (such as ESP8266/ESP32 on Haier ACs).
- **Decision:** Implement a 350ms debounce window in `AcController`. Rapid taps immediately update the UI with a pending indicator, while network transmission is coalesced into a single command containing the final clamped temperature.
- **Consequences:** Prevents socket buffer flooding while maintaining instant UI responsiveness.

---

## [DEC-005] Self-Contained Local Toolchain (Corretto JDK 17 & Android SDK 34)
- **Date:** 2026-11-07
- **Status:** Approved / Implemented
- **Context:** The host machine lacked Java, Gradle, or the Android SDK on system PATH.
- **Decision:** Provision Amazon Corretto JDK 17 and Android SDK Command-Line Tools directly into `.tools/` within the project root. Configure `local.properties` to target this local SDK and manage builds via the Gradle wrapper (`gradlew.bat`).
- **Consequences:** Zero pollution of system PATH. The project is completely self-contained and builds reproducibly on the developer workstation.

---

## [DEC-006] Structural Exclusion of Simulator from Release Builds
- **Date:** 2026-11-07
- **Status:** Approved / Implemented
- **Context:** The Master Prompt strictly mandates that the simulation driver must never appear in the release APK or be mistaken for verified hardware control.
- **Decision:** Place `SimulatedAcDriver` in `app/src/debug/kotlin/` and provide `AdditionalDrivers.getDrivers()` differently between debug and release source sets (`app/src/debug` vs `app/src/release`).
- **Consequences:** The compiler physically excludes the simulator code and assets from the release APK. It is structurally impossible for simulation code to ship in release builds.

---

## [DEC-007] Protocol Driver Strategy: SmartAir2 & HaiSmart Implemented; hOn as Stub
- **Date:** 2026-11-07
- **Status:** Approved / Implemented
- **Context:** Multiple Haier Smart AC generations exist in the wild (SmartAir2, HaiSmart/U+, and hOn).
- **Decision:**
  - `driver-smartair2`: Full implementation of binary TCP port 56800 protocol (MIT-licensed reference code from `bstuff/haier-ac-remote` and `fastfend/HaierACBridge`).
  - `driver-haismart`: Full implementation of AES-128-CBC TCP port 56800 protocol (MIT-licensed reference code from `enapt/haismart-local`).
  - `driver-hon`: Explicit stub reporting that hOn is a cloud-only protocol without direct local control.
  - None of the candidate drivers are marked `VERIFIED_ON_REAL_AC` until physical confirmation on Hardware Day.
- **Consequences:** Provides immediate local control candidates covering the two known Haier local protocols without guessing or inventing protocol details.

---

## [DEC-008] Android Keystore Encryption for Secrets
- **Date:** 2026-11-07
- **Status:** Approved / Implemented
- **Context:** HaiSmart local protocol requires storing a 16-byte local key.
- **Decision:** Encrypt `localKeyHex` using AES-256-GCM backed by the Android Keystore (`RoomAcKeystoreKey`) before persisting to SharedPreferences.
- **Consequences:** Credentials are secure against cleartext extraction, and `DiagnosticsLogger` redacts any 32-character hex key from runtime logs.
