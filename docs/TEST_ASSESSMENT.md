# Test Suite Assessment & Verification Log

**Date:** 2026-11-08  
**Total Unit Tests:** 32 unique tests (36 executions across Debug and Release variants)  
**Failures / Errors:** 0  

---

## 1. Module-by-Module Test Breakdown

### 1. `:core-domain` (`AcControllerTest`)
- **Location**: `core-domain/src/test/kotlin/com/roomac/controller/domain/AcControllerTest.kt`
- **Tests Executed**: 3
- **What It Proves**:
  1. `rapidTemperatureStepsAreDebouncedAndCoalesced`: Verifies that rapid user clicks on the +/- temperature controls are coalesced into a single consolidated command, without dropping or duplicating in-flight transmissions.
  2. `heatingModeBlockedWhenCapabilitiesReportNoHeating`: Proves that attempting to switch to Heat mode when `capabilities.supportsHeating == false` is blocked in the use case and emits an explanatory `disabledReason`.
  3. `temperatureAdjustmentClampedToCapabilityBounds`: Verifies that temperature targets cannot exceed `minTemperature` (e.g. 16.0°C) or `maxTemperature` (e.g. 30.0°C).

### 2. `:core-protocol` (`DefaultAcRepositoryTest`)
- **Location**: `core-protocol/src/test/kotlin/com/roomac/controller/protocol/DefaultAcRepositoryTest.kt`
- **Tests Executed**: 4
- **What It Proves**:
  1. `executeCommandWithoutConfigFailsWithNoAcConfigured`: Ensures commands fail gracefully with a typed `AcError.NoAcConfigured` when no device configuration has been provided.
  2. `commandSafetyGateBlocksTemperatureWhenPowerIsUnknown`: **Critical Safety Test**. Verifies that when device power state is `UNKNOWN` (unconfirmed readback and no asserted baseline), mutating commands (temperature) are strictly blocked with `AcError.CommandBlocked`.
  3. `configuredDriverExecutesPowerSuccessfully`: Verifies that a valid configured driver executes power commands and updates state flow.
  4. `driverFailureReturnsTypedError`: Verifies that transport/driver exceptions are wrapped and propagated as typed `AcError` objects.

### 3. `:driver-smartair2` (`SmartAir2DriverTest`)
- **Location**: `driver-smartair2/src/test/kotlin/com/roomac/controller/driver/smartair2/SmartAir2DriverTest.kt`
- **Tests Executed**: 7
- **What It Proves**:
  1. `controlPacketEncodesPowerAtCorrectOffset`: Proves that the outgoing command frame places the power flag at offset index 10 (not offset 8).
  2. `controlPacketChecksumIsValidSumMod256`: Proves the single layout table calculates the valid sum mod 256 checksum across all 35 payload bytes.
  3. `statusPacketDecodesPowerAtCorrectOffset`: Proves that the incoming status response frame reads the power flag from offset index 8 (not offset 10).
  4. `statusPacketDecodesModeTemperatureAndIndoorTemp`: Proves correct extraction of mode, target temperature (+16 formula), and indoor temperature at offset index 26.
  5. `statusPacketDecodesVerticalSwing`: Verifies swing decoding mapping to `SwingPosition.OSCILLATE` vs `OFF`.
  6. `corruptChecksumReturnsNull`: Ensures malformed or corrupt response frames are cleanly rejected without altering application state.
  7. `shortStatusPacketReturnsNull`: Ensures packets shorter than 37 bytes are rejected.

### 4. `:driver-haismart` (`HaiSmartDriverTest`)
- **Location**: `driver-haismart/src/test/kotlin/com/roomac/controller/driver/haismart/HaiSmartDriverTest.kt`
- **Tests Executed**: 6
- **What It Proves**:
  1. `cipherEncryptDecryptRoundtripMatchesPlaintext`: Verifies AES-128-CBC cipher consistency using 16-byte key and custom IV.
  2. `invalidKeyLengthThrowsIllegalArgumentException`: Validates that keys not exactly 16 bytes fail fast.
  3. `buildPacketEncodesHeaderAndLength`: Proves framing header `0x55 0xAA` and length fields are assembled correctly.
  4. `parsePacketValidatesMagicAndChecksum`: Proves invalid magic or bad checksum packets return null.
  5. `payloadParsingExtractsPowerModeAndTemp`: Proves payload field parsing from decrypted data.
  6. `shortPayloadReturnsNull`: Ensures truncated payloads do not cause out-of-bounds exceptions or yield fabricated defaults.

### 5. `:driver-hon` (`HonDriverTest`)
- **Location**: `driver-hon/src/test/kotlin/com/roomac/controller/driver/hon/HonDriverTest.kt`
- **Tests Executed**: 6
- **What It Proves**:
  1. `driverExposesStubVerificationStatus`: Confirms `DriverVerificationStatus.STUB_MISSING_INFO`.
  2. `connectAlwaysFailsGracefully`: Confirms connecting fails with an explanatory message.
  3. `executeCommandReturnsProtocolNotVerified`: Proves that commands are blocked from network transmission.
  4. `probeConnectionReturnsRefusedOrUnreachable`: Proves passive probe does not claim connectivity.
  5. `capabilitiesReflectUnverifiedDefaults`: Checks that capabilities are conservative (`supportsHeating=false`).
  6. `driverDescriptionStatesCloudLocked`: Confirms honest documentation in UI description.

### 6. `:app` (`DiagnosticsLoggerTest` & `ErrorMappingTest`)
- **Location**: `app/src/test/kotlin/com/roomac/controller/`
- **Tests Executed**: 6 (4 in DiagnosticsLoggerTest, 2 in ErrorMappingTest)
- **What It Proves**:
  1. `thirtyTwoCharHexKeyIsCompletelyRedacted`: Proves 32-character hex keys are replaced with `[REDACTED_HEX_KEY]` with zero partial character reveal.
  2. `shortHexStringIsNotRedacted`: Proves diagnostic hex packets (e.g. `55aa`) are preserved for debugging.
  3. `passwordWithColonOrEqualsIsRedacted`: Proves password/token assignments are redacted to `password=[REDACTED]`.
  4. `ringBufferRetainsMaximumEntries`: Proves the 200-entry eviction ring buffer prevents memory leaks.
  5. `allDomainErrorsMapToNonEmptyUserMessages`: Proves every `AcError` sealed subclass maps to an informative user-facing string.
  6. `permissionMissingSuggestsSettings`: Checks permission error UX guidance.

