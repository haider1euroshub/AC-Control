# Defect Register & Resolution Audit

**Describes Commit:** `399997bf9aba1a8702eb9aa9f7371f294b9c8695` + `recovery/software-ready` branch
**Date:** 2026-11-08

---

## 1. Defect Register

| Defect ID | Module / File | Description & Root Cause | Severity | Status | Fix Implemented | Regression Test |
|---|---|---|:---:|:---:|---|---|
| **A1** | `driver-smartair2/SmartAir2Driver.kt`<br>`driver-haismart/HaiSmartDriver.kt` | When assembling control frames, unconfirmed state was replaced with fabricated defaults (`mode = COOL`, `temp = 24.0`, `swing = OFF`). | High | **FIXED** | Replaced default fallbacks with strict safety validation: returns `CommandResult.Failed(AcError.CommandBlocked)` if any required field is unknown. | `SmartAir2DriverTest.executeCommandWithoutKnownStateBlocksWithTypedError`<br>`HaiSmartDriverTest.executeCommandWithoutKeyReturnsAuthOrKeyRejected` |
| **A4** | `core-protocol/DriverConnectionManager.kt`<br>`core-domain/AcModels.kt` | Disconnection or peer socket EOF set `isReachable = false` but left `CONFIRMED_BY_AC` values unchanged, enabling stale state to serve as a control basis. | Medium | **FIXED** | Added `StateProvenance.STALE`. On disconnect or EOF, all confirmed state transitions to `STALE`. `ValueWithProvenance.isKnown` rejects STALE as a control basis. | `DefaultAcRepositoryTest.commandSafetyGateBlocksTemperatureWhenPowerIsStale`<br>`AcControllerTest.staleProvenanceIsNotKnownForControlBasisButHasDisplayValue` |
| **B1** | `app/SetupScreen.kt`<br>`app/SetupViewModel.kt` | Baseline power and mode chip buttons in Setup had empty click handlers (`onClick = { /* Could expose setter if needed */ }`). | Medium | **FIXED** | Hooked up `onBaselinePowerSelected(PowerState)` and `onBaselineModeSelected(AcMode)` in `SetupViewModel` and UI. | UI action verified in `SetupScreen.kt` |
| **G1** | `app/MainScreen.kt` | Power button check `uiState.acState.power.value == PowerState.ON` drew unknown power as "TURN ON" (mimicking OFF state). | Medium | **FIXED** | Rendered tri-state: Unknown power displays as "POWER (Unknown)" with secondary container styling; stale power displays as "TURN ON/OFF (Stale)". | Verified in Compose tree rendering logic |
| **G2** | `app/MainScreen.kt` | Swing position check `uiState.acState.swing.value?.vertical ?: SwingPosition.OFF` rendered unknown swing as "Fixed" (OFF). | Medium | **FIXED** | When swing is unknown, shows explicit descriptive text and does not select any position chip. | Verified in `MainScreen.kt` |
| **K1** | `app/build.gradle.kts` | Release build configuration used `signingConfig = signingConfigs.getByName("debug")`, incorrectly producing debug-signed release APKs. | High | **FIXED** | Removed debug signing configuration from release block; enabled R8 minification. Output is explicitly `app-release-unsigned.apk`. | `assembleRelease` produces unsigned APK |
