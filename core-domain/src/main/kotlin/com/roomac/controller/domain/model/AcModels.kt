package com.roomac.controller.domain.model

enum class PowerState {
    OFF,
    ON
}

enum class AcMode {
    COOL,
    HEAT,
    OTHER // e.g. dry, fan, auto set by remote; app does not switch TO this, but accurately reports it
}

enum class SwingPosition {
    OFF,
    OSCILLATE,
    POSITION_1,
    POSITION_2,
    POSITION_3,
    POSITION_4,
    POSITION_5
}

data class SwingState(
    val vertical: SwingPosition = SwingPosition.OFF,
    val horizontal: SwingPosition = SwingPosition.OFF
)

/**
 * Provenance tracking for every state value to prevent fabricating unconfirmed hardware defaults.
 */
enum class StateProvenance {
    CONFIRMED_BY_AC,        // Verified directly from a valid parsed status packet from the AC
    OPTIMISTIC_AFTER_SEND,  // In-flight command transmitted over network; awaiting confirmation
    OWNER_ASSERTED,         // Explicitly entered by the owner in Setup (Bring-up mode)
    UNKNOWN                 // No confirmed or asserted value exists
}

data class ValueWithProvenance<T>(
    val value: T?,
    val provenance: StateProvenance = StateProvenance.UNKNOWN
) {
    val isKnown: Boolean get() = value != null && provenance != StateProvenance.UNKNOWN

    companion object {
        fun <T> unknown(): ValueWithProvenance<T> = ValueWithProvenance(null, StateProvenance.UNKNOWN)
        fun <T> confirmed(value: T): ValueWithProvenance<T> = ValueWithProvenance(value, StateProvenance.CONFIRMED_BY_AC)
        fun <T> optimistic(value: T): ValueWithProvenance<T> = ValueWithProvenance(value, StateProvenance.OPTIMISTIC_AFTER_SEND)
        fun <T> ownerAsserted(value: T): ValueWithProvenance<T> = ValueWithProvenance(value, StateProvenance.OWNER_ASSERTED)
    }
}

data class AcState(
    val power: ValueWithProvenance<PowerState> = ValueWithProvenance.unknown(),
    val mode: ValueWithProvenance<AcMode> = ValueWithProvenance.unknown(),
    val targetTemperature: ValueWithProvenance<Double> = ValueWithProvenance.unknown(),
    val swing: ValueWithProvenance<SwingState> = ValueWithProvenance.unknown(),
    val indoorTemperature: ValueWithProvenance<Double> = ValueWithProvenance.unknown(),
    val isReachable: Boolean = false,
    val lastUpdatedEpochMs: Long = 0L
) {
    /**
     * Checks if all fields required to safely assemble a full control frame are known.
     * Prevents overriding real AC power with fabricated OFF or defaults.
     */
    val isFullyKnownForControl: Boolean
        get() = power.isKnown && mode.isKnown && targetTemperature.isKnown && swing.isKnown
}

enum class TemperatureUnit {
    CELSIUS,
    FAHRENHEIT
}

enum class CapabilitySource {
    REPORTED_BY_AC,
    DOCUMENTED_BY_DRIVER,
    ENTERED_BY_OWNER,
    ASSUMED_DEFAULT
}

data class AcCapabilities(
    val supportsHeating: Boolean = false,
    val minTemperature: Double = 16.0,
    val maxTemperature: Double = 30.0,
    val temperatureStep: Double = 1.0,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val supportedVerticalSwings: Set<SwingPosition> = setOf(SwingPosition.OFF, SwingPosition.OSCILLATE),
    val supportedHorizontalSwings: Set<SwingPosition> = setOf(SwingPosition.OFF),
    val source: CapabilitySource = CapabilitySource.ASSUMED_DEFAULT,
    val areCapabilitiesKnown: Boolean = false
)

/**
 * Baseline state asserted by the owner in Setup (Bring-up mode) to safely bootstrap control
 * before readback packet parsing is proven on physical hardware.
 */
data class OwnerAssertedBaseline(
    val power: PowerState = PowerState.ON,
    val mode: AcMode = AcMode.COOL,
    val targetTemperature: Double = 24.0,
    val verticalSwing: SwingPosition = SwingPosition.OSCILLATE,
    val horizontalSwing: SwingPosition = SwingPosition.OFF,
    val isEnabled: Boolean = false
)

sealed class AcCommand {
    data class SetPower(val power: PowerState) : AcCommand()
    data class SetMode(val mode: AcMode) : AcCommand()
    data class SetTargetTemperature(val target: Double) : AcCommand()
    data class SetVerticalSwing(val position: SwingPosition) : AcCommand()
    data class SetHorizontalSwing(val position: SwingPosition) : AcCommand()
    object RefreshState : AcCommand()
}

sealed class CommandResult {
    data class Confirmed(val newState: AcState) : CommandResult()
    data class SentUnconfirmed(val pendingState: AcState) : CommandResult()
    data class Failed(val error: AcError) : CommandResult()
}

sealed class CommandLifecycle {
    object Idle : CommandLifecycle()
    data class Pending(val command: AcCommand) : CommandLifecycle()
    data class Confirmed(val command: AcCommand) : CommandLifecycle()
    data class SentUnconfirmed(val command: AcCommand) : CommandLifecycle()
    data class Failed(val command: AcCommand, val error: AcError) : CommandLifecycle()
    data class TimedOut(val command: AcCommand) : CommandLifecycle()
}

sealed class AcError(val userMessage: String, val technicalDetail: String = "") {
    object NoAcConfigured : AcError(
        "No Air Conditioner configured. Please visit Setup to connect your AC.",
        "Configuration missing"
    )

    data class CommandBlocked(val reason: String) : AcError(
        "Command blocked by safety gate: $reason",
        "CommandBlocked: $reason"
    )

    object NoWifi : AcError(
        "Wi-Fi is disconnected. Please connect your phone to the same Wi-Fi network as the AC.",
        "No active Wi-Fi interface"
    )

    object DifferentNetwork : AcError(
        "Phone appears to be on a different network subnet than the AC.",
        "Subnet mismatch"
    )

    object PermissionMissing : AcError(
        "Local network access permission is required to communicate with the AC.",
        "Missing NEARBY_WIFI_DEVICES or local network permission"
    )

    data class AcUnreachable(val host: String) : AcError(
        "Cannot reach AC at $host. Verify the AC is powered on at the wall breaker.",
        "TCP connection refused or unreachable host"
    )

    object Timeout : AcError(
        "The AC did not respond within the expected time limit.",
        "Socket read timeout"
    )

    object AuthOrKeyRejected : AcError(
        "Authentication or local encryption key was rejected by the AC.",
        "AES decryption or key handshake error"
    )

    data class DecryptFailed(val detail: String) : AcError(
        "Decryption failed: $detail. Verify the 16-byte key.",
        detail
    )

    data class ProtocolNotVerified(val protocolName: String) : AcError(
        "Protocol '$protocolName' is unverified on hardware or not implemented for local LAN control.",
        "Unverified driver"
    )

    data class CapabilityUnsupported(val feature: String) : AcError(
        "The AC does not support $feature according to its capabilities.",
        "Feature unsupported by physical hardware"
    )

    data class UnexpectedResponse(val detail: String) : AcError(
        "Received unexpected response from AC: $detail",
        detail
    )

    data class GeneralError(val message: String) : AcError(
        message,
        message
    )
}
