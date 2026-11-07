# BUILD AND TEST REPORT

This document contains factual, verifiable evidence of build execution, test outcomes, APK artifact sizes, and test suite coverage.

---

## 1. APK Build Audit

### 1.1 Debug APK Status: ✅ **EXISTS**
- **File Path**: `app\build\outputs\apk\debug\app-debug.apk`
- **Exact Size**: **15,730,126 bytes** (~15.73 MB)
- **Last Modified Timestamp**: `2026-11-07 19:20:30 PKT`
- **Metadata**: Verified with `output-metadata.json`:
  ```json
  {
    "version": 3,
    "artifactType": { "type": "APK" },
    "applicationId": "com.roomac.controller.debug",
    "variantName": "debug",
    "elements": [{
      "type": "SINGLE",
      "versionCode": 1,
      "versionName": "1.0.0-debug",
      "outputFile": "app-debug.apk"
    }]
  }
  ```

### 1.2 Release APK Status: ⏳ **NOT PRESENT IN OUTPUT DIRECTORY**
- **File Path**: `app\build\outputs\apk\release\app-release.apk`
- **Status**: The release APK was not compiled during the fast unit verification pass. The project `app/build.gradle.kts` configures the release build type with `isMinifyEnabled = false` and assigns `signingConfig = signingConfigs.getByName("debug")` for personal installation.
- Running `:app:assembleRelease` invokes `lintVitalAnalyzeRelease`, which requires several minutes to execute in background on Windows.

---

## 2. JVM Unit Test Execution Evidence

### 2.1 Complete Gradle Test Run Output
Command executed:
```powershell
$env:JAVA_HOME = "c:\Users\Hex Ali\Desktop\AC Control\.tools\jdk17.0.21_0"
$env:GRADLE_USER_HOME = "c:\Users\Hex Ali\Desktop\AC Control\.tools\gradle_home"
.\gradlew.bat --no-daemon --console=plain test
```

**Result:**
```
BUILD SUCCESSFUL in 1m 42s
67 actionable tasks: 15 executed, 52 up-to-date
```

### 2.2 Tasks Executed & Passed
- `:core-domain:compileTestKotlin` & `:core-domain:test` -> **PASS**
- `:core-protocol:compileTestFixturesKotlin` & `:core-protocol:testFixturesJar` -> **PASS**
- `:core-protocol:compileTestKotlin` & `:core-protocol:test` -> **PASS**
- `:driver-smartair2:compileTestKotlin` & `:driver-smartair2:test` -> **PASS**
- `:driver-haismart:compileTestKotlin` & `:driver-haismart:test` -> **PASS**
- `:driver-hon:compileTestKotlin` & `:driver-hon:test` -> **PASS**
- `:app:compileDebugUnitTestKotlin` & `:app:testDebugUnitTest` -> **PASS**
- `:app:compileReleaseUnitTestKotlin` & `:app:testReleaseUnitTest` -> **PASS**

---

## 3. Test Inventory & Scope

| Test File | Module | What It Actually Tests |
|---|---|---|
| `AcControllerTest.kt` | `:core-domain` | Verifies debounce timing (350ms), temperature step clamping, mode rejection when heating unsupported, and power toggle state logic. |
| `DefaultAcRepositoryTest.kt` | `:core-protocol` | Verifies command lifecycle (`Pending`, `Confirmed`, `TimedOut`), 4000ms timeout handling, and configuration application. |
| `DriverContractTest.kt` | `:core-protocol` (testFixtures) | Contract tests verifying driver ID, displayName, driver verification status constraint (must not claim `VERIFIED_ON_REAL_AC`), and non-crashing command dispatch. |
| `SmartAir2DriverTest.kt` | `:driver-smartair2` | Verifies checksum algorithm, packet frame byte offsets (power at 10, mode at 11, temp at 12, swing at 14/15), and corrupt packet rejection. Extends `DriverContractTest`. |
| `HaiSmartDriverTest.kt` | `:driver-haismart` | Verifies AES-128-CBC encryption/decryption symmetry, packet framing, and driver contract. Extends `DriverContractTest`. |
| `HonDriverTest.kt` | `:driver-hon` | Verifies that the hOn driver cleanly returns `ProtocolNotVerified` without throwing unexpected exceptions. Extends `DriverContractTest`. |
| `DiagnosticsLoggerTest.kt` | `:app` | Verifies redaction of passwords (`password=[REDACTED]`), 32-character hex keys, and ring-buffer 200-entry eviction. |
| `ErrorMappingTest.kt` | `:app` | Verifies human-readable error messages for all `AcError` sealed types. |

---

## 4. Emulator and Physical Device Testing Status

- **JVM Unit Tests**: **PASSED (100%)**.
- **Android Emulator Tests**: **NOT PERFORMED**. No active Android emulator or AVD was booted.
- **Physical Device Testing**: **NOT PERFORMED**. No USB debugging device connected; no real AC unit connected.
- **Summary**: All code compiles cleanly and passes all unit tests, but runtime on real Android ART runtime and real Wi-Fi network awaits hardware test day.

