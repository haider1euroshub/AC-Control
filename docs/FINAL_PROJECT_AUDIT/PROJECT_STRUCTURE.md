# COMPLETE PROJECT STRUCTURE AUDIT

This document provides a detailed catalog of the entire relevant codebase for the **Room AC Controller** multi-module Android project.

---

## 1. Directory Tree Overview

```
c:\Users\Hex Ali\Desktop\AC Control
├── app/                                # Android Application Module (UI, ViewModels, DI, Storage)
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── kotlin/com/roomac/controller/
│       │       ├── MainActivity.kt
│       │       ├── RoomAcApplication.kt
│       │       ├── diagnostics/
│       │       │   └── DiagnosticsLogger.kt
│       │       ├── storage/
│       │       │   └── EncryptedConfigStorage.kt
│       │       ├── ui/
│       │       │   ├── main/
│       │       │   │   ├── MainScreen.kt
│       │       │   │   ├── MainScreenPreviews.kt
│       │       │   │   └── MainViewModel.kt
│       │       │   ├── setup/
│       │       │   │   ├── SetupScreen.kt
│       │       │   │   └── SetupViewModel.kt
│       │       │   └── theme/
│       │       │       └── Theme.kt
│       └── test/kotlin/com/roomac/controller/
│           ├── DiagnosticsLoggerTest.kt
│           └── ErrorMappingTest.kt
├── core-domain/                        # Pure Kotlin Domain Models & Business Logic
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/roomac/controller/domain/
│       │   ├── model/
│       │   │   └── AcModels.kt
│       │   ├── repository/
│       │   │   └── AcRepository.kt
│       │   └── usecase/
│       │       └── AcController.kt
│       └── test/kotlin/com/roomac/controller/domain/
│           └── AcControllerTest.kt
├── core-protocol/                      # Protocol Abstractions, Registry, Repository & Test Fixtures
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/roomac/controller/protocol/
│       │   ├── AcDriver.kt
│       │   └── DefaultAcRepository.kt
│       ├── testFixtures/kotlin/com/roomac/controller/protocol/
│       │   └── DriverContractTest.kt
│       └── test/kotlin/com/roomac/controller/protocol/
│           ├── DefaultAcRepositoryTest.kt
│           └── FakeAcDriver.kt
├── driver-smartair2/                   # Haier SmartAir2 Binary Protocol (Port 56800 Unencrypted)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/roomac/controller/driver/smartair2/
│       │   ├── SmartAir2Driver.kt
│       │   └── SmartAir2Packet.kt
│       └── test/kotlin/com/roomac/controller/driver/smartair2/
│           └── SmartAir2DriverTest.kt
├── driver-haismart/                    # Haier HaiSmart Protocol (Port 56800 AES-128 Encrypted)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/roomac/controller/driver/haismart/
│       │   ├── HaiSmartCipher.kt
│       │   ├── HaiSmartDriver.kt
│       │   └── HaiSmartPacket.kt
│       └── test/kotlin/com/roomac/controller/driver/haismart/
│           └── HaiSmartDriverTest.kt
├── driver-hon/                         # Haier hOn Stub Protocol (Cloud-Only Ecosystem)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/roomac/controller/driver/hon/
│       │   └── HonDriver.kt
│       └── test/kotlin/com/roomac/controller/driver/hon/
│           └── HonDriverTest.kt
├── docs/                               # Documentation, Audit Logs & Hardware Day Guides
│   ├── FINAL_PROJECT_AUDIT/
│   │   ├── COMPLETE_PROJECT_AUDIT.md
│   │   └── PROJECT_STRUCTURE.md
│   ├── ARCHITECTURE.md
│   ├── BUILD_AND_INSTALL.md
│   ├── DECISIONS.md
│   ├── FINAL_HARDWARE_SETUP.md
│   ├── LOCAL_CONTROL_FINDINGS.md
│   ├── OWNER_CHECKLIST.md
│   ├── PROJECT_AUDIT.md
│   ├── REQUIRED_INFORMATION.md
│   └── TEST_LOG.md
├── build.gradle.kts                    # Root build configuration
├── settings.gradle.kts                 # Multi-module declarations
└── gradlew.bat                         # Gradle wrapper executable
```

---

## 2. Module Responsibilities and Boundaries

| Module | Type | Purpose | Dependencies |
|---|---|---|---|
| `:core-domain` | Kotlin JVM | Pure business entities, state models, use case `AcController` | `kotlinx-coroutines-core` |
| `:core-protocol` | Kotlin JVM + `testFixtures` | `AcDriver` interface, `DefaultAcRepository`, `DriverRegistry`, and shared test fixture `DriverContractTest` | `:core-domain`, `kotlinx-coroutines-core` |
| `:driver-smartair2`| Kotlin JVM | Implements binary frame codec and socket communication for SmartAir2 modules | `:core-domain`, `:core-protocol`, `testFixtures(:core-protocol)` |
| `:driver-haismart` | Kotlin JVM | Implements AES-128-CBC encryption, packet codec, and socket communication for HaiSmart | `:core-domain`, `:core-protocol`, `testFixtures(:core-protocol)` |
| `:driver-hon` | Kotlin JVM | Stub driver documenting cloud lock-in for hOn series | `:core-domain`, `:core-protocol`, `testFixtures(:core-protocol)` |
| `:app` | Android App | Compose UI, ViewModels, Android Keystore encryption storage, lifecycle | All core & driver modules, AndroidX Compose, Material3, Coroutines |

---

## 3. Key Source Files and Responsibilities

### App Module
- **`MainActivity.kt`**: Single-activity entry point. Hosts standard Compose navigation between `MainScreen` and `SetupScreen`.
- **`RoomAcApplication.kt`**: Application subclass. Manages app-level coroutine scope, instantiates `DriverRegistry`, initializes `EncryptedConfigStorage`, restores saved credentials, and connects to the saved protocol driver on app launch.
- **`DiagnosticsLogger.kt`**: In-memory ring buffer (200 records max) providing real-time logging and string export, with regex redaction for keys, passwords, and tokens.
- **`EncryptedConfigStorage.kt`**: Stores device host, port, MAC, and local encryption keys. Uses Android KeyStore with AES-256-GCM encryption.
- **`MainScreen.kt`**: Primary Compose UI screen displaying power toggle, temperature target and current temp, cool/heat mode selector, vertical and horizontal swing buttons, and live connection status banner.
- **`SetupScreen.kt`**: Setup interface to select protocol driver, configure IP/Port/Key, run live socket connection tests, and view redacted diagnostic logs.

### Protocol Drivers
- **`SmartAir2Driver.kt` & `SmartAir2Packet.kt`**: Implements unencrypted TCP protocol over port 56800. Encodes query (`FF FF 00 0A ...`) and control (`FF FF 00 0E ...`) frames with modulo-256 checksums.
- **`HaiSmartDriver.kt`, `HaiSmartPacket.kt`, & `HaiSmartCipher.kt`**: Implements AES-128-CBC encrypted framing over port 56800. Requires a 16-byte key extracted from the Haier mobile app or pairing handshake.
- **`HonDriver.kt`**: Clear stub returning `ProtocolNotVerified` error, explaining that hOn firmware does not expose unauthenticated local control.

