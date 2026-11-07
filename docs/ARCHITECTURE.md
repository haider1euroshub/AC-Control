# SYSTEM ARCHITECTURE — ROOM AC CONTROLLER

## 1. Modular Architecture Overview

The codebase is strictly organized into six clean, decoupled Gradle modules:

```
AC Control/
├── core-domain/         # Pure Kotlin. Models, Repository interface, Use Cases (AcController).
├── core-protocol/       # Pure Kotlin. AcDriver interface, DriverRegistry, DefaultAcRepository.
├── driver-smartair2/    # Pure Kotlin. Binary framing on port 56800 (SmartAir2 protocol).
├── driver-haismart/     # Pure Kotlin. AES-128-CBC framing on port 56800 (HaiSmart protocol).
├── driver-hon/          # Pure Kotlin. Stub for hOn cloud-only protocol.
└── app/                 # Android Jetpack Compose app. UI, ViewModel, Keystore storage, Diagnostics.
```

### Dependency Graph

```
                   ┌──────────────┐
                   │ core-domain  │
                   └──────┬───────┘
                          ▲
                          │
                   ┌──────┴───────┐
                   │core-protocol │
                   └──────┬───────┘
            ▲             ▲             ▲
            │             │             │
┌───────────┴────┐ ┌──────┴────────┐ ┌──┴─────────┐
│driver-smartair2│ │driver-haismart│ │ driver-hon  │
└───────────┬────┘ └──────┬────────┘ └──┬─────────┘
            ▲             ▲             ▲
            └──────────┐  │  ┌──────────┘
                       │  │  │
                    ┌──┴──┴──┴───┐
                    │    app     │
                    └────────────┘
```

- `core-domain` has **zero external framework dependencies**. It contains pure business models and domain logic.
- `core-protocol` defines the `AcDriver` contract and orchestrates connection state and timeouts.
- Each `driver-*` module is an isolated implementation of `AcDriver`.
- `app` contains the Android-specific code: Jetpack Compose Material 3 UI, ViewModels, Android Keystore encryption for secrets, and Diagnostics logger.

---

## 2. Driver Contract & Life Cycle

Every protocol module implements the `AcDriver` interface:

```kotlin
interface AcDriver {
    val id: String
    val displayName: String
    val verificationStatus: DriverVerificationStatus
    val description: String

    val connectionStatus: StateFlow<ConnectionStatus>
    val stateFlow: StateFlow<AcState>
    val capabilitiesFlow: StateFlow<AcCapabilities>

    suspend fun connect(config: DeviceConfig): Result<Unit>
    suspend fun disconnect()
    suspend fun executeCommand(command: AcCommand): CommandResult
    suspend fun testConnection(config: DeviceConfig): Result<String>
}
```

### Command Lifecycle

1. User interacts with UI (e.g. presses Temperature `+`).
2. `AcController` debounces rapid taps (350ms window) and clamps within `capabilities.minTemperature`..`maxTemperature`.
3. `DefaultAcRepository` emits `CommandLifecycle.Pending(command)`.
4. The active `AcDriver` formats the packet, connects if needed, and writes to TCP socket port 56800.
5. If the AC sends back state confirmation, the driver parses it and returns `CommandResult.Confirmed(newState)`.
6. If the AC does not confirm state within the command timeframe, `CommandResult.SentUnconfirmed(pendingState)` is returned. The UI renders this clearly as *"sent, not confirmed"*.
7. If the socket fails or times out (4000ms default), `CommandResult.Failed(AcError.Timeout)` is emitted.

---

## 3. Simulator Isolation

- `SimulatedAcDriver` resides exclusively in `app/src/debug/kotlin/com/roomac/controller/simulator/`.
- `AdditionalDrivers.getDrivers()` provides `SimulatedAcDriver` in debug builds.
- In `app/src/release/kotlin/com/roomac/controller/AdditionalDrivers.kt`, `getDrivers()` returns an empty list.
- **Result:** The simulator is physically excluded by the Kotlin compiler from the release APK. No test code or fake device can ever leak into the release variant.

---

## 4. Security & Privacy

1. **No External Network Dependencies:** The app communicates exclusively with the AC's local IP address over LAN (cleartext traffic permitted specifically for local RFC1918 subnets).
2. **Encrypted Storage:** Secrets such as the HaiSmart `localKeyHex` are encrypted using AES-256-GCM via the Android Keystore (`EncryptedConfigStorage`).
3. **Diagnostics Redaction:** `DiagnosticsLogger` automatically redacts 16-64 character hex strings and password fields before storing or exporting logs.

