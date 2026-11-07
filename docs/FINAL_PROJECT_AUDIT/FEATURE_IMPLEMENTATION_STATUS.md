# FEATURE IMPLEMENTATION STATUS AUDIT

This document evaluates the 4 core requested control capabilities:
1. **Power ON/OFF**
2. **Heating / Cooling Mode Selection**
3. **Temperature Increase / Decrease and Target Display**
4. **Fan Swing / Movement Control (Vertical & Horizontal)**

---

## 1. Feature 1: Power ON / OFF

### Implementation Breakdown
- **UI (`MainScreen.kt`)**: 
  - Central prominent power button with animated color transitions (Cyan/Orange when active, grey/dimmed when off).
  - Shows clear text label: "TAP TO TURN ON" or "TAP TO TURN OFF".
  - Displays progress indicators when command is pending.
- **ViewModel (`MainViewModel.kt`)**: 
  - Calls `controller.onPowerClicked(_uiState.value.acState)`.
- **Domain Logic (`AcController.kt`)**: 
  - Computes target toggle (`if (current == ON) OFF else ON`).
  - Calls `repository.setPower(next)`.
- **Repository (`DefaultAcRepository.kt`)**: 
  - Wraps execution in `AcCommand.SetPower(power)`.
  - Sets `CommandLifecycle.Pending(command)` and enforces 4-second timeout.
- **Driver Layer**:
  - **SmartAir2 (`SmartAir2Packet.kt`)**: Encodes `POWER_ON` (`0x01`) or `POWER_OFF` (`0x00`) at byte offset 10 of control frame.
  - **HaiSmart (`HaiSmartPacket.kt`)**: Encodes power byte in plaintext command payload prior to AES-128 encryption.
  - **hOn (`HonDriver.kt`)**: Explicit stub returning `ProtocolNotVerified`.
- **Hardware Transmission**: 
  - Real TCP socket payload is sent when driver is connected to target host:port.
- **Device Response Handling**: 
  - Confirmed state readback updates `AcState.power`. In the absence of readback, marks state as `SentUnconfirmed`.
- **Real Hardware Testing**: ❌ **Not Tested on Physical Unit.**
- **Completeness**: **100% in code.** Ready for hardware transmission.

---

## 2. Feature 2: Heating / Cooling Mode Selection

### Implementation Breakdown
- **UI (`MainScreen.kt`)**: 
  - Mode selector chips for **Cool** (snowflake icon) and **Heat** (sun icon).
  - The Heat chip is disabled or hidden if `capabilities.supportsHeating == false` (e.g. cooling-only AC).
  - Selected mode is visually highlighted.
- **ViewModel (`MainViewModel.kt`)**: 
  - Calls `controller.onModeSelected(mode, capabilities)`.
- **Domain Logic (`AcController.kt`)**: 
  - Safeguard: Returns early if user requests `AcMode.HEAT` but `capabilities.supportsHeating` is false.
  - Dispatches `repository.setMode(mode)`.
- **Repository (`DefaultAcRepository.kt`)**: 
  - Wraps in `AcCommand.SetMode(mode)` and updates lifecycle.
- **Driver Layer**:
  - **SmartAir2 (`SmartAir2Packet.kt`)**: Encodes `MODE_COOL` (`0x02`) or `MODE_HEAT` (`0x04`) at byte offset 11 of control frame.
  - **HaiSmart (`HaiSmartPacket.kt`)**: Encodes mode byte in AES-encrypted payload.
  - **hOn (`HonDriver.kt`)**: Explicit stub.
- **Hardware Transmission**: 
  - Transmits exact mode byte over active TCP socket.
- **Device Response Handling**: 
  - Updates `AcState.mode` on response parse or triggers unconfirmed state.
- **Real Hardware Testing**: ❌ **Not Tested on Physical Unit.**
- **Completeness**: **100% in code.** Supports cooling-only configuration safeguard.

---

## 3. Feature 3: Temperature Increase / Decrease & Target Display

### Implementation Breakdown
- **UI (`MainScreen.kt`)**: 
  - Circular dashboard display showing target temperature in large bold text (`24°C`) and ambient indoor temperature if returned by AC.
  - Dedicated `+` and `−` floating action buttons for stepped adjustments.
  - Responsive visual update when pending temperature is debouncing.
- **ViewModel (`MainViewModel.kt`)**: 
  - Subscribes to `controller.pendingTemperature` to display immediate responsive feedback to user touch before network transmission.
- **Domain Logic (`AcController.kt`)**: 
  - **Debouncing & Coalescing**: Incorporates a 350ms debounce window. If the user taps `+` three times rapidly, local UI shows +1, +2, +3 immediately, but only **one** combined TCP command is transmitted over Wi-Fi once tapping ceases.
  - **Bounds Clamping**: Clamps to `capabilities.minTemperature` (16.0°C) and `capabilities.maxTemperature` (30.0°C).
- **Repository (`DefaultAcRepository.kt`)**: 
  - Dispatches `AcCommand.SetTargetTemperature(target)`.
- **Driver Layer**:
  - **SmartAir2 (`SmartAir2Packet.kt`)**: Encodes target temperature as `(target.toInt() - 16).toByte()` at byte offset 12.
  - **HaiSmart (`HaiSmartPacket.kt`)**: Encodes target temperature into payload before AES encryption.
  - **hOn (`HonDriver.kt`)**: Explicit stub.
- **Hardware Transmission**: 
  - Transmits coalesced target temperature byte over TCP socket.
- **Device Response Handling**: 
  - Confirmed state readback updates confirmed target temperature and indoor ambient temperature.
- **Real Hardware Testing**: ❌ **Not Tested on Physical Unit.**
- **Completeness**: **100% in code.** Debounce and clamping verified by unit tests.

---

## 4. Feature 4: Fan Swing / Movement Control

### Implementation Breakdown
- **UI (`MainScreen.kt`)**: 
  - Vertical swing selector card offering supported positions: Auto / Oscillate, Top, Middle-High, Middle, Middle-Low, Bottom, and Off.
  - Horizontal swing selector card (Auto / Off).
  - Dynamically hides or disables unsupported positions based on `capabilities.supportedVerticalSwings`.
- **ViewModel (`MainViewModel.kt`)**: 
  - Routes to `controller.onVerticalSwingSelected` and `controller.onHorizontalSwingSelected`.
- **Domain Logic (`AcController.kt`)**: 
  - Validates that requested swing enum exists in `capabilities.supportedVerticalSwings` / `capabilities.supportedHorizontalSwings`.
- **Repository (`DefaultAcRepository.kt`)**: 
  - Emits `AcCommand.SetVerticalSwing` or `AcCommand.SetHorizontalSwing`.
- **Driver Layer**:
  - **SmartAir2 (`SmartAir2Packet.kt`)**:
    - Vertical Swing: Encodes `0x00` (Off), `0x01` (Auto/Oscillate), `0x02` - `0x06` (Positions 1 to 5) at byte offset 14.
    - Horizontal Swing: Encodes `0x00` (Off) or `0x01` (Auto/Oscillate) at byte offset 15.
  - **HaiSmart (`HaiSmartPacket.kt`)**:
    - Encodes swing bytes into AES-encrypted payload structure.
  - **hOn (`HonDriver.kt`)**: Explicit stub.
- **Hardware Transmission**: 
  - Transmitted over active TCP socket.
- **Device Response Handling**: 
  - Updates `AcState.swing` from response packet.
- **Real Hardware Testing**: ❌ **Not Tested on Physical Unit.**
- **Completeness**: **100% in code.** Supports independent vertical and horizontal vanes.

---

## 5. Summary Status Matrix

| Feature | UI Status | Domain Logic | Protocol Transmit Code | Readback Handling | Hardware Verified | Overall Readiness |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| **Power ON/OFF** | Complete | Complete | Complete (Binary & AES) | Complete | ❌ Pending | **Ready for HW Test** |
| **Cool / Heat Mode** | Complete | Complete | Complete (Binary & AES) | Complete | ❌ Pending | **Ready for HW Test** |
| **Temperature Control** | Complete | Complete (Debounced) | Complete (Binary & AES) | Complete | ❌ Pending | **Ready for HW Test** |
| **Fan Swing Control** | Complete | Complete (Capabilities-aware) | Complete (Binary & AES) | Complete | ❌ Pending | **Ready for HW Test** |

