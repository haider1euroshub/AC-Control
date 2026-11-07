# Room AC Controller

An Android application designed to control a Haier Smart Wi-Fi Air Conditioner over **local Wi-Fi only** without any cloud dependencies, external accounts, tracking, or subscription services.

> **IMPORTANT NOTICE REGARDING HARDWARE STATUS**:
> This codebase has been thoroughly tested with unit and simulated tests, but **has NOT yet been tested on the physical Haier AC unit**.
> Protocol drivers are based on reverse-engineered open-source documentation. Every control field in the app clearly displays its provenance (`Confirmed`, `Optimistic`, `Owner-Asserted`, or `Unknown`).

---

## Supported Features (Strictly 4 Core Functions)

1. **Power Control**: Power ON / OFF with safety gate verification.
2. **Mode Selection**: Switch between Cool and Heat mode (Heat mode is conservative and gated unless your unit's heating support is confirmed).
3. **Temperature Control**: Increment/decrement target temperature with honest numeric display (`--` when unconfirmed).
4. **Fan Swing Control**: Vertical oscillating swing control.

---

## Supporting Infrastructure

- **Setup & Configuration**: Configure target AC IP, port, protocol driver, and optional local encryption key.
- **Passive Connection Probe**: Test socket connectivity non-destructively without transmitting control commands.
- **Owner-Asserted Baseline**: Safely bootstrap controls if the AC readback format is not yet verified on hardware.
- **Diagnostics Log**: Real-time log viewer with strict redaction of encryption keys, passwords, and tokens.
- **Hardware Keystore Security**: AES-GCM encrypted local storage without insecure fallbacks.

---

## Architecture

- **Pattern**: MVVM with Kotlin Coroutines and StateFlow.
- **UI**: Jetpack Compose with Material 3.
- **Multi-Module**:
  - `:app`: Android presentation, storage, and diagnostics.
  - `:core-domain`: Pure Kotlin business logic and provenance safety models.
  - `:core-protocol`: Network connection manager, frame codecs, and driver contracts.
  - `:driver-smartair2`: SmartAir2 driver implementation.
  - `:driver-haismart`: HaiSmart AES-128 driver implementation.
  - `:driver-hon`: Safe stub driver for unverified hOn protocols.

---

## Documentation

- [Physical Hardware Bring-Up Guide](docs/HARDWARE_BRINGUP.md)
- [Protocol Verification Status Matrix](docs/VERIFICATION_STATUS.md)
- [Public Protocol Evidence Log](docs/PROTOCOL_EVIDENCE.md)
- [Android Compatibility & Permissions](docs/ANDROID_COMPAT.md)
- [Changelog](docs/CHANGELOG.md)

