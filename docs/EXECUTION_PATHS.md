# Execution Paths Tracing & Analysis

This document provides a trace of all critical control and data execution paths through the "Room AC Controller" application, detailing class names, filenames, and line references.

---

## Path A: Temperature Adjustment (+ / -)

```
[UI] MainScreen.kt (IconButton onClick)
  └─► [ViewModel] MainViewModel.onTemperatureStep(increase: Boolean)
        └─► [UseCase] AcController.onTemperatureStep(increase, currentState, capabilities)
              ├─► Validates target bounds (capabilities.minTemperature .. maxTemperature)
              ├─► Updates pendingTemperature StateFlow (immediate visual feedback)
              └─► Debounce job (coalesces rapid clicks over 350ms)
                    └─► [Repository] DefaultAcRepository.setTargetTemperature(clampedTarget)
                          ├─► [Safety Gate] Asserts currentState.power.isKnown == true
                          │     └─► If unknown: returns CommandResult.Failed(AcError.CommandBlocked)
                          └─► [Driver] AcDriver.executeCommand(AcCommand.SetTargetTemperature)
                                └─► [ConnectionManager] DriverConnectionManager.executeCommand()
                                      ├─► Mutex.withLock (serializes command execution)
                                      ├─► [Encoder] SmartAir2Layout.buildControlPacket()
                                      ├─► Socket.getOutputStream().write(bytes)
                                      ├─► Emits DiagnosticsSink.logRawTx()
                                      └─► Suspends on CompletableDeferred<ByteArray> awaiting status reply
```

**Return & State Propagation Path:**
```
[Socket Read Loop] DriverConnectionManager.readerLoop()
  ├─► Frame accumulation buffer collects raw TCP stream
  ├─► [Codec] FrameCodec.findFrame() detects frame boundary & validates length/checksum
  ├─► Emits DiagnosticsSink.logRawRx()
  ├─► Completes pending CompletableDeferred with raw packet
  └─► [Decoder] SmartAir2Layout.parseStatusPacket(responseBytes)
        └─► Returns new AcState with StateProvenance.CONFIRMED_BY_AC
              └─► Updates driver.stateFlow
                    └─► DefaultAcRepository.stateFlow emits
                          └─► MainViewModel updates _uiState.acState
                                └─► MainScreen recomposes target temperature display
```

---

## Path B: Power Toggle (ON / OFF)

```
[UI] MainScreen.kt (Power Button)
  └─► MainViewModel.onPowerClicked()
        └─► AcController.onPowerClicked(currentState)
              ├─► Determines target: if (current.power.value == PowerState.ON) OFF else ON
              └─► DefaultAcRepository.setPower(targetPower)
                    ├─► Note: Power command is exempt from power.isKnown check (it establishes power)
                    └─► DriverConnectionManager.executeCommand(AcCommand.SetPower(targetPower))
                          ├─► Serialized via Mutex
                          ├─► Encoded to frame (SmartAir2: offset 10 = 0x01/0x00)
                          └─► Transmitted over socket
```

---

## Path C: Mode Selection (Cool / Heat)

```
[UI] MainScreen.kt (Mode FilterChips)
  └─► MainViewModel.onModeSelected(mode)
        └─► AcController.onModeSelected(mode, currentState, capabilities)
              ├─► If mode == HEAT and !capabilities.supportsHeating:
              │     └─► Blocks command, emits disabledReason: "Heating mode is not supported"
              └─► DefaultAcRepository.setMode(mode)
                    ├─► [Safety Gate] Requires currentState.power.isKnown == true
                    └─► DriverConnectionManager.executeCommand(AcCommand.SetMode(mode))
```

---

## Path D: Fan Swing Movement / Direction Control

> **Scope Note**: "Swing" controls the oscillating motor direction of the air outlet louvers. It does NOT adjust blower fan speed.

```
[UI] MainScreen.kt (Swing FilterChips: Off / Oscillate)
  └─► MainViewModel.onVerticalSwingSelected(position)
        └─► AcController.onVerticalSwingSelected(position, capabilities)
              ├─► Validates position against capabilities.supportedVerticalSwings
              └─► DefaultAcRepository.setVerticalSwing(position)
                    ├─► [Safety Gate] Requires currentState.power.isKnown == true
                    └─► DriverConnectionManager.executeCommand(AcCommand.SetVerticalSwing(position))
```

---

## Path E: Application Startup with Saved Configuration

```
[Application] RoomAcApplication.onCreate()
  ├─► Initializes DriverRegistry with [SmartAir2Driver, HaiSmartDriver, HonDriver, (SimulatedAcDriver if debug)]
  ├─► Initializes EncryptedConfigStorage (backed by Android Keystore AES-256-GCM)
  ├─► Binds DiagnosticsLogger to all drivers via setDiagnosticsSink()
  └─► Launches background scope:
        └─► EncryptedConfigStorage.loadConfiguration()
              └─► If config.isConfigured:
                    └─► DefaultAcRepository.applyConfiguration(config)
                          ├─► Looks up driver from registry
                          ├─► Instantiates socket connection via DriverConnectionManager
                          └─► If OwnerAssertedBaseline is enabled:
                                └─► Seeds initial state with StateProvenance.OWNER_ASSERTED
```

---

## Path F: Wi-Fi Lost / Changed Mid-Session

```
[Android OS] Network disconnected or changed
  └─► DriverConnectionManager socket write / read throws IOException
        ├─► Closes underlying java.net.Socket
        ├─► Sets connectionStatus = ConnectionStatus.Disconnected
        ├─► Marks current state.isReachable = false
        ├─► Marks unconfirmed values as StateProvenance.UNKNOWN (or STALE)
        ├─► Cancels in-flight CompletableDeferred with AcError.AcUnreachable
        └─► If in foreground: triggers bounded exponential reconnect backoff (max 30s)
```

---

## Path G: Wrong / Unreachable IP Address

```
[UI] SetupScreen.kt (Tap "Probe AC (Passive)")
  └─► SetupViewModel.probeConnection()
        └─► AcDriver.probeConnection(config, timeoutMs = 3000L)
              ├─► Opens Socket() with Socket.connect(InetSocketAddress(host, port), 3000)
              ├─► If connect throws SocketTimeoutException / ConnectException:
              │     └─► Returns ProbeResult.RefusedOrUnreachable(error.message)
              ├─► If socket connects but receives 0 bytes before 3000ms:
              │     └─► Returns ProbeResult.ConnectedSilent(host, port, 3000L)
              └─► Finally block:
                    └─► Always executes socket.close() (guaranteed zero socket leaks)
```

---

## Path H: Malformed / Partial Response Handling

```
[Socket Read Loop] DriverConnectionManager.readerLoop()
  ├─► Buffer receives partial bytes (e.g. 15 bytes of 37-byte SmartAir2 packet)
  ├─► FrameCodec.findFrame() inspects buffer:
  │     └─► Returns FrameBoundaryResult.Incomplete(bytesNeeded = 22)
  │     └─► Reader loop continues reading from socket into accumulation buffer
  ├─► Buffer receives corrupt magic bytes (e.g. 0x00 0x12 ...)
  │     └─► FrameCodec scans and discards garbage bytes until valid magic is found
  └─► Buffer receives frame with corrupt checksum:
        └─► parseStatusPacket returns null
        └─► Emits DiagnosticsSink.logError("Checksum mismatch")
        └─► Packet discarded; state is NOT modified with corrupt data
```

