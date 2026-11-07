# Current State & Architecture Overview

## Project Architecture

The application is structured into a clean multi-module architecture:

```
                  ┌───────────────────────┐
                  │         :app          │  (Jetpack Compose UI, ViewModels, Android Keystore Storage)
                  └───────────┬───────────┘
                              │
               ┌──────────────┴──────────────┐
               ▼                             ▼
     ┌───────────────────┐         ┌───────────────────┐
     │   :core-domain    │         │  :core-protocol   │  (DriverRegistry, ConnectionManager, FrameCodec)
     └───────────────────┘         └─────────┬─────────┘
                                             │
                       ┌─────────────────────┼─────────────────────┐
                       ▼                     ▼                     ▼
             ┌───────────────────┐ ┌───────────────────┐ ┌───────────────────┐
             │ :driver-smartair2 │ │ :driver-haismart  │ │   :driver-hon     │
             └───────────────────┘ └───────────────────┘ └───────────────────┘
```

## Module Responsibilities

1. **`:core-domain`**:
   - Pure Kotlin module with zero Android dependencies.
   - Domain models: `AcState`, `ValueWithProvenance`, `AcCapabilities`, `OwnerAssertedBaseline`, `AcCommand`, `CommandResult`.
   - Use Cases: `AcController` (command debounce, capability-aware parameter validation, optimistic state tracking).

2. **`:core-protocol`**:
   - Pure Kotlin module defining communication contracts.
   - `AcDriver`: Interface for all protocol drivers including `probeConnection` and `DiagnosticsSink`.
   - `DriverConnectionManager`: Single-reader, single-writer network engine handling framing, frame accumulation, socket timeouts, and correlation.
   - `DefaultAcRepository`: Enforces the **Command Safety Gate** (blocks state mutation if current power is unknown).

3. **`:driver-smartair2`**:
   - SmartAir2 binary protocol implementation (`0xFF 0xFF` magic).
   - Single authoritative layout definitions (`SmartAir2Layout`).
   - Frame boundary parsing, checksum calculation, and status decoding.

4. **`:driver-haismart`**:
   - HaiSmart protocol implementation (`0x55 0xAA` magic).
   - AES-128-CBC encryption and decryption of payload data.
   - Packet assembly with header, payload length, and checksum.

5. **`:driver-hon`**:
   - Safe stub driver returning `AcError.ProtocolNotVerified("hOn")`.
   - Prevents unsafe blind LAN transmissions until verified credentials or captures are provided.

6. **`:app`**:
   - UI layer built with Jetpack Compose and Material 3.
   - `MainViewModel` and `MainScreen`: Four core controls (Power, Mode, Temp +/- with display, Fan Swing) with explicit provenance badges (`Confirmed`, `Optimistic`, `Asserted`, `Unknown`).
   - `SetupViewModel` and `SetupScreen`: Passive probe tool, Owner-Asserted Baseline toggle, driver selection, network settings.
   - `EncryptedConfigStorage`: Keystore-backed AES-GCM credential persistence with fail-closed behavior (no unencrypted fallback).
   - `DiagnosticsLogger`: Ring buffer logger with comprehensive regex redaction of keys and secrets.

