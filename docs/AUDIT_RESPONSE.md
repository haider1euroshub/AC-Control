# AUDIT RESPONSE: VERIFICATION OF FINDINGS

**Date:** 2026-11-07  
**Evaluation Scope:** Code audit of findings across all modules (`:app`, `:core-domain`, `:core-protocol`, `:driver-smartair2`, `:driver-haismart`, `:driver-hon`).

---

## 1. Critical Findings

### [C] Inconsistent Packet Indexing & Incomplete Parsing in `SmartAir2Packet.kt`
- **Status:** **CONFIRMED**
- **Evidence:**
  - In `SmartAir2Packet.buildControlPacket` (lines 89-94):
    - Index 0-1: Header `FF FF`
    - Index 2-3: Length `00 0E`
    - Index 4-7: Reserved `00 00 00 00`
    - Index 8-9: Command `00 0C`
    - Index 10: `powerByte`
    - Index 11: `modeByte`
    - Index 12: `tempByte`
    - Index 13: Fan placeholder `0x00`
    - Index 14: `vertSwingByte`
    - Index 15: `horizSwingByte`
    - Index 16: Checksum
  - In `SmartAir2Packet.parseStatusPacket` (lines 115-121):
    - Index 8 reads `power`
    - Index 9 reads `mode`
    - Index 10 reads `temp`
    - Index 11 reads `vert`
  - **Conflict:** Offsets 8-11 in the response are read directly without verifying the message type or length field. Length field (`raw[2]`, `raw[3]`) is never checked. Horizontal swing (`horizSwing`) and fan speed are ignored and never parsed. In `buildControlPacket`, fan is hardcoded to `0x00`.

---

### [S] Unverified Framing, Unused MAC, Missing Handshake
- **Status:** **CONFIRMED**
- **Evidence:**
  - `SmartAir2Driver.kt` and `SmartAir2Packet.kt`: No handshake, pairing sequence, or session initialization exists.
  - In `SmartAir2Driver.connect(config)`: `config.macAddress` is passed in `DeviceConfig` but never read or encoded.
  - Checksum is purely a modulo-256 sum. If certain firmware variants use a CRC16 or require registration with MAC/IP, this implementation will fail silently on real hardware.

---

### [C] Fabricated Default State in Control Frames
- **Status:** **CONFIRMED**
- **Evidence:**
  - In `SmartAir2Driver.kt` line 30: `_state = MutableStateFlow(AcState(isReachable = false))`
  - In `AcModels.kt` lines 40-47: Default `AcState` has `power = OFF`, `mode = COOL`, `targetTemperature = 24.0`, `swing = OFF`.
  - In `SmartAir2Driver.executeCommand` (lines 138-147):
    - When user executes `AcCommand.SetTargetTemperature(target)`:
      ```kotlin
      SmartAir2Packet.buildControlPacket(
          power = cur.power.value,   // if unconfirmed, sends POWER_OFF!
          mode = cur.mode.value,     // sends default COOL!
          targetTemperature = command.target,
          verticalSwing = cur.swing.value.vertical,
          horizontalSwing = cur.swing.value.horizontal
      )
      ```
    - **Impact:** If the user adjusts temperature while power state has not been read from hardware, the app sends `POWER_OFF` and `24.0°C` defaults, overriding the physical AC state.

---

### [C] Double InputStream Reader & Unsynchronized Framing
- **Status:** **CONFIRMED**
- **Evidence:**
  - In `SmartAir2Driver.kt`:
    - Method `startListening()` (lines 208-230) runs a continuous `while (isActive)` loop calling `inStream.read(buffer)`.
    - Method `executeCommand()` (lines 178-189) directly calls `inStream.read(buffer)` on the exact same stream!
  - **Impact:** Race condition. The background listening loop and the command execution method compete for bytes from the single TCP socket `InputStream`. Bytes belonging to a single packet can be split between both readers. Furthermore, a single `.read()` is assumed to return an entire frame without verifying length-delimited boundaries or buffering partial reads.

---

### [C] Timeout Swallowing and Broken Cancellation in Repository & Driver
- **Status:** **CONFIRMED**
- **Evidence:**
  - In `SmartAir2Driver.kt` line 70: `sock.soTimeout = 4000`
  - In `DefaultAcRepository.kt` line 15: `commandTimeoutMs = 4000L`
  - In `DefaultAcRepository.execute` (lines 98-104):
    ```kotlin
    val result = withTimeoutOrNull(commandTimeoutMs) {
        try {
            driver.executeCommand(command)
        } catch (e: Exception) {
            CommandResult.Failed(AcError.GeneralError(e.message ?: "Execution error"))
        }
    }
    ```
  - **Impact:**
    1. Catching `Exception` catches `CancellationException`, defeating coroutine structured cancellation.
    2. Sockets do not unblock on coroutine cancellation unless explicitly closed.
    3. `SocketTimeoutException` is caught as a generic `Exception` inside the driver, masking it as `GeneralError` instead of `Timeout`.

---

## 2. High Findings

### [C] DiagnosticsLogger Disconnected from Drivers & Logcat
- **Status:** **CONFIRMED**
- **Evidence:**
  - `DiagnosticsLogger.kt` is located in `:app:com.roomac.controller.diagnostics`.
  - Drivers in `:driver-smartair2` and `:driver-haismart` cannot access `:app` (cyclic dependency prevented by Gradle).
  - No interface exists in `:core-protocol` or `:core-domain` for drivers to log raw hex packets or socket lifecycle events.
  - `DiagnosticsLogger` does not call `android.util.Log` or Logcat.

---

### [C] Hardcoded Capabilities Without Verification
- **Status:** **CONFIRMED**
- **Evidence:**
  - In `SmartAir2Driver.kt` lines 34-52:
    - Hardcodes `supportsHeating = true`
    - Hardcodes `minTemperature = 16.0`, `maxTemperature = 30.0`
    - Hardcodes `supportedVerticalSwings` with 5 discrete positions
    - Sets `areCapabilitiesKnown = true`
  - **Truth:** Real SmartAir2 units vary widely: some are cooling-only, and most SmartAir2 UART specifications describe only swing on/off (oscillate vs static), not 5 discrete motorized flap steps.

---

### [S] Unverified Port 56800 and HaiSmart Protocol Scheme
- **Status:** **CONFIRMED**
- **Evidence:**
  - `HaiSmartDriver.kt` assumes port 56800, AES-128-CBC with zero IV, and a pre-shared 16-byte key.
  - Zero documentation or capture files exist in the project proving this key derivation or framing matches the user's specific physical hardware.

---

## 3. Medium Findings

### [C] Debounce Cancellation & Display Glitches in `AcController.kt`
- **Status:** **CONFIRMED**
- **Evidence:**
  - In `AcController.kt` lines 48-55:
    ```kotlin
    temperatureDebounceJob?.cancel()
    temperatureDebounceJob = coroutineScope.launch {
        delay(debounceDelayMs)
        val targetToSend = _pendingTemperature.value ?: clamped
        _pendingTemperature.value = null
        repository.setTargetTemperature(targetToSend)
    }
    ```
  - If a user taps again while `repository.setTargetTemperature(targetToSend)` is in-flight, `temperatureDebounceJob?.cancel()` cancels the network transmission mid-request.
  - `_pendingTemperature.value = null` is set before the network call finishes, causing the UI to revert to the old confirmed temperature momentarily before updating.

---

### [C] Unsafe Base64 Fallback in `EncryptedConfigStorage.kt`
- **Status:** **CONFIRMED**
- **Evidence:**
  - In `EncryptedConfigStorage.kt` lines 110-113:
    ```kotlin
    } catch (_: Exception) {
        // JVM / Fallback for local unit testing
        Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
    ```
  - If Android KeyStore fails on a device, the pre-shared key is saved in **unencrypted Base64 plaintext** to SharedPreferences.
  - In `RoomAcApplication.onCreate()`, `configStorage.loadConfiguration()` executes synchronously on the main UI thread.

---

### [C] Diagnostics Redaction Flaws
- **Status:** **CONFIRMED**
- **Evidence:**
  - Hex key regex leaves first 4 and last 4 characters visible (`${hex.take(4)}...[REDACTED]...${hex.takeLast(4)}`).
  - Password pattern only matches `=`: `key=xyz` is matched, but `key: xyz`, `key : "xyz"`, or JSON forms are ignored.

---

### [C] Stale Connection & Inadequate Connection Probe
- **Status:** **CONFIRMED**
- **Evidence:**
  - `socket.isConnected` returns true even after the remote peer closes the TCP socket.
  - In `SmartAir2Driver.testConnection(config)`:
    - Does not use `use {}` or a `finally` block; socket remains open if an exception occurs after connection.
    - Does not distinguish between connection refused, connection established but silent, or valid protocol responses.

---

### [C] Manifest Redundancies & Unused Permissions
- **Status:** **CONFIRMED**
- **Evidence:**
  - `android:usesCleartextTraffic="true"` only applies to HTTP traffic; raw TCP sockets do not consult network security config.
  - `CHANGE_NETWORK_STATE` is declared in `AndroidManifest.xml` but never used anywhere in source code.

---

### [C] Duplicate Temperature Stepping Logic
- **Status:** **CONFIRMED**
- **Evidence:**
  - `DefaultAcRepository.adjustTemperatureStep` and `AcController.onTemperatureStep` duplicate logic for reading current target, stepping, and dispatching.

