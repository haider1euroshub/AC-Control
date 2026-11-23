package com.roomac.controller.domain.model

/**
 * Operating modes. [stdCode] is the Haier STD operation-mode code (0 auto, 1 cool, 2 dry, 4 heat,
 * 6 fan only); other protocol adapters keep their own wire mapping and use only the enum.
 */
enum class AcMode(val stdCode: Int, val label: String) {
    AUTO(0, "Auto"),
    COOL(1, "Cool"),
    DRY(2, "Dry"),
    HEAT(4, "Heat"),
    FAN(6, "Fan");

    companion object {
        fun fromStdCode(code: Int): AcMode? = entries.firstOrNull { it.stdCode == code }
    }
}

/** Fan speeds. [stdCode] is the Haier STD code (1 high, 2 medium, 3 low, 5 auto); other adapters map their own. */
enum class FanSpeed(val stdCode: Int, val label: String) {
    AUTO(5, "Auto"),
    LOW(3, "Low"),
    MEDIUM(2, "Medium"),
    HIGH(1, "High");

    companion object {
        fun fromStdCode(code: Int): FanSpeed? = entries.firstOrNull { it.stdCode == code }
    }
}

/** ECO / power-limit level. Higher levels cap compressor current harder. */
enum class EcoLevel(val wireValue: Int, val label: String) {
    OFF(0, "Off"),
    LEVEL_1(5, "L1"),
    LEVEL_2(6, "L2"),
    LEVEL_3(7, "L3");

    companion object {
        fun fromWire(value: Int): EcoLevel? = entries.firstOrNull { it.wireValue == value }
    }
}

/** On/off functions carried as single flags in the appliance's control block. */
enum class AcFeature(val label: String) {
    TURBO("Turbo"),
    QUIET("Quiet"),
    SLEEP("Sleep"),
    HEALTH("Health"),
    DISPLAY("Display light"),
    ENERGY_SAVING("Energy saving")
}

/**
 * A full status snapshot decoded from one report the appliance sent. Every field here is what the AC
 * itself reported; nothing is filled in from what the user last tapped. A `null` means the report did
 * not carry that reading (e.g. a sensor the unit does not have).
 */
data class AcStatus(
    val power: Boolean,
    val mode: AcMode?,
    val rawModeCode: Int,
    val targetTemperatureC: Double,
    val fanSpeed: FanSpeed?,
    val rawFanCode: Int,
    val verticalSwing: Boolean,
    val horizontalSwing: Boolean?,
    val indoorTemperatureC: Double?,
    val outdoorTemperatureC: Double?,
    val features: Map<AcFeature, Boolean>,
    val ecoLevel: EcoLevel?,
    val selfCleaning: Boolean?,
    val heatCapable: Boolean?,
    val errorCode: Int?,
    /** True when the report layout is fully recognised and its control block may seed a write. */
    val writable: Boolean,
    /** Report length in bytes, kept for diagnostics and layout identification. */
    val reportLength: Int,
    val receivedAtEpochMs: Long,
    /**
     * Names of the properties the unit returned, for protocols that report named properties (e.g.
     * Gree). Used to offer only controls the unit actually has. Empty for fixed-layout protocols.
     */
    val reportedProperties: Set<String> = emptySet()
)

/**
 * What the app is allowed to offer for a device. Built from the appliance's own report plus its
 * model profile, never from assumptions: a control is listed only when both agree it exists.
 */
data class AcCapabilities(
    val modes: List<AcMode>,
    val fanSpeeds: List<FanSpeed>,
    val minTemperatureC: Double,
    val maxTemperatureC: Double,
    val temperatureStepC: Double,
    val verticalSwing: Boolean,
    val horizontalSwing: Boolean,
    val features: Set<AcFeature>,
    val ecoLevels: List<EcoLevel>,
    val selfClean: Boolean,
    val writable: Boolean,
    /** False where the unit is known to drop Auto fan while in Fan mode (confirmed for Haier classic). */
    val autoFanInFanMode: Boolean = true
)

/** A single user-requested change. */
sealed class AcCommand {
    data class SetPower(val on: Boolean) : AcCommand()
    data class SetMode(val mode: AcMode) : AcCommand()
    data class SetTargetTemperature(val celsius: Double) : AcCommand()
    data class SetFanSpeed(val speed: FanSpeed) : AcCommand()
    data class SetVerticalSwing(val on: Boolean) : AcCommand()
    data class SetHorizontalSwing(val on: Boolean) : AcCommand()
    data class SetFeature(val feature: AcFeature, val on: Boolean) : AcCommand()
    data class SetEcoLevel(val level: EcoLevel) : AcCommand()
    object StartSelfClean : AcCommand()

    val description: String
        get() = when (this) {
            is SetPower -> if (on) "Power on" else "Power off"
            is SetMode -> "Mode ${mode.label}"
            is SetTargetTemperature -> "Set ${formatTemp(celsius)}"
            is SetFanSpeed -> "Fan ${speed.label}"
            is SetVerticalSwing -> "Up/down swing ${if (on) "on" else "off"}"
            is SetHorizontalSwing -> "Left/right swing ${if (on) "on" else "off"}"
            is SetFeature -> "${feature.label} ${if (on) "on" else "off"}"
            is SetEcoLevel -> "ECO ${level.label}"
            StartSelfClean -> "Start self-clean"
        }

    /** Whether [status] shows this command took effect. Used to verify every command by read-back. */
    fun isReflectedIn(status: AcStatus): Boolean = when (this) {
        is SetPower -> status.power == on
        is SetMode -> status.mode == mode
        is SetTargetTemperature -> status.targetTemperatureC == celsius
        is SetFanSpeed -> status.fanSpeed == speed
        is SetVerticalSwing -> status.verticalSwing == on
        is SetHorizontalSwing -> status.horizontalSwing == on
        is SetFeature -> status.features[feature] == on
        is SetEcoLevel -> status.ecoLevel == level
        StartSelfClean -> status.selfCleaning == true
    }
}

fun formatTemp(celsius: Double): String =
    if (celsius % 1.0 == 0.0) "${celsius.toInt()}°C" else "${"%.1f".format(celsius)}°C"

/** Outcome of a command, always based on what the appliance reported back. */
sealed class CommandOutcome {
    /** The AC accepted the change and a status report read back afterwards shows it applied. */
    data class Confirmed(val status: AcStatus) : CommandOutcome()

    /** The AC explicitly refused the command (EPP refusal frame) or reported a state without it. */
    data class Rejected(val reason: String, val status: AcStatus?) : CommandOutcome()

    /** The command could not be delivered or verified. */
    data class Failed(val error: AcError) : CommandOutcome()
}

/**
 * " (Step: sign-in · code 30032)" for account errors, so the user and diagnostics see which step of
 * sign-in → device list → key fetch → local handshake failed and the service's own reason code.
 */
private fun failedAt(step: String?, code: String?): String {
    val parts = listOfNotNull(step?.let { "Step: $it" }, code?.takeIf { it.isNotEmpty() }?.let { "code $it" })
    return if (parts.isEmpty()) "" else " (${parts.joinToString(" · ")})"
}

/**
 * Errors the user can see. Every message names a practical next step and never includes key
 * material or raw payloads.
 */
sealed class AcError(val userMessage: String, val nextStep: String) {
    object WifiOff : AcError(
        "Wi-Fi is turned off.",
        "Turn on Wi-Fi and join the same network as the air conditioner."
    )
    object NotOnWifi : AcError(
        "The phone is not connected to a Wi-Fi network.",
        "Join the home Wi-Fi network the air conditioner uses. Mobile data cannot reach the AC."
    )
    object AcNotFound : AcError(
        "No supported air conditioner answered on this network.",
        "Check the AC is powered at the wall and its Wi-Fi light is on. Guest Wi-Fi, client isolation, VLANs and VPNs block local discovery."
    )
    data class Unreachable(val host: String) : AcError(
        "The air conditioner is not answering.",
        "Check the AC has power and is on Wi-Fi. The app will search the network again."
    )
    object ServiceUnavailable : AcError(
        "The AC refused the local control connection.",
        "Power-cycle the AC at the breaker, wait one minute and try again."
    )
    object SessionBusy : AcError(
        "The AC closed the connection. Another app or hub may be connected to it locally.",
        "Close other apps or hubs controlling this AC (Home Assistant, the manufacturer's app on the same Wi-Fi), then retry."
    )
    object HandshakeRefused : AcError(
        "The AC refused the connection.",
        "Remove this AC in the app and add it again."
    )
    object KeyMissing : AcError(
        "This AC has not been connected yet.",
        "Tap \"Reconnect\" and the app will set it up automatically."
    )
    object KeyInvalid : AcError(
        "The AC did not accept the saved connection key.",
        "Tap \"Reconnect\": the app fetches a fresh key by itself."
    )
    /** The AC needs a one-time sign-in to the manufacturer's account before its key can be fetched. */
    data class AccountRequired(val brand: String, val service: String) : AcError(
        "Sign in to your $brand account once so the app can connect to this AC.",
        "Tap \"Sign in\" and use the same email or phone number and password as in the $service app."
    )
    /** The manufacturer refused the sign-in (wrong password, unknown account) or no longer accepts the saved one. */
    data class AccountRejected(val service: String, val step: String? = null, val code: String? = null) : AcError(
        "The $service account did not accept the sign-in." + failedAt(step, code),
        "Check the email or phone number and password are the ones you use in the $service app, then try again."
    )
    /**
     * The service found no account with this email or phone number in the chosen country. Haier gives
     * the same answer for an account that exists but was registered under another country.
     */
    data class AccountNotInRegion(val service: String, val step: String? = null, val code: String? = null) : AcError(
        "No $service account with this email or phone number was found in the selected country." + failedAt(step, code),
        "Choose the country that was selected when the $service account was created (it can differ from where you live), and check the email or phone number."
    )
    /**
     * The manufacturer's service refused this app itself, not the account: the account and any saved
     * sign-in are untouched. Seen when the app identity is wrong; also the symptom of the service
     * blocking the app.
     */
    data class ServiceRefusedApp(val brand: String, val step: String? = null, val code: String? = null) : AcError(
        "$brand's online service is not accepting requests from this version of RoomAC. Your account is not the problem." + failedAt(step, code),
        "ACs already connected keep working on your Wi-Fi. Install the latest version of RoomAC."
    )
    /** The manufacturer's online service could not be reached. Says nothing about the account. */
    data class CloudUnavailable(val service: String, val step: String? = null, val code: String? = null) : AcError(
        "Could not reach the $service online service." + failedAt(step, code),
        "Check the phone has internet access, then try again. This is needed only when an AC is first connected."
    )
    /** Signed in, but the selected AC is not one of the account's devices. */
    data class DeviceNotInAccount(val service: String, val step: String? = null, val code: String? = null) : AcError(
        "This AC is not in the signed-in $service account." + failedAt(step, code),
        "This is an account binding issue, not an app fault. Add the AC in the $service app with this account, or sign in here with the account that set it up."
    )
    /** The account is signed in and lists the AC, but the key service would not issue its key. */
    data class KeyNotIssued(val service: String, val code: String? = null) : AcError(
        "The $service service did not issue a key for this AC." + failedAt("key fetch", code),
        "Try again later. If it keeps failing, open the AC once in the $service app, then tap \"Try again\"."
    )
    /** This build of the app cannot sign in to the brand's service (its app identity was left out of the build). */
    data class AccountUnavailable(val brand: String) : AcError(
        "$brand sign-in is not configured in this build of RoomAC.",
        "This build was made without the $brand sign-in settings. Install a build that includes them (see BUILD_AND_INSTALL.md). ACs already connected keep working."
    )
    data class ProvisioningFailed(val detail: String) : AcError(
        "The AC did not accept automatic pairing. $detail",
        "Check the AC is switched on at the wall and on the same Wi-Fi, then tap \"Scan again\"."
    )
    data class KeyRotated(val deviceVersion: Int, val heldVersion: Int) : AcError(
        "The AC has changed its local key (now version $deviceVersion, saved key is version $heldVersion).",
        "Tap \"Reconnect\": the app fetches the new key by itself."
    )
    data class UnsupportedLayout(val reportLength: Int) : AcError(
        "This AC sends a $reportLength-byte status layout that the app cannot control safely yet.",
        "Status is read-only for this model. Share a diagnostics export so the layout can be added."
    )
    object ProtocolMismatch : AcError(
        "The device answered, but not with the expected local protocol.",
        "Remove this AC in the app and add it again."
    )
    object Timeout : AcError(
        "The AC did not answer in time.",
        "Move closer to the router or retry. If it keeps happening, power-cycle the AC."
    )
    object DisconnectedDuringCommand : AcError(
        "The connection dropped while the command was being sent.",
        "Check the current state on the dashboard before retrying, because the change may or may not have applied."
    )
    data class CommandRefused(val code: Int?) : AcError(
        "The AC refused this command" + (code?.let { " (reason code $it)" } ?: "") + ".",
        "The setting may not be allowed in the current mode. Change mode or power and try again."
    )
    data class NotSupported(val what: String) : AcError(
        "$what is not supported by this air conditioner.",
        "This control is not available for this model."
    )
    data class InvalidValue(val detail: String) : AcError(
        "Invalid value: $detail.",
        "Choose a value within the range shown."
    )
    /** Sign-up: the service refuses more verification codes for this address for now. Retrying makes it worse. */
    data class TooManyCodes(val service: String) : AcError(
        "$service has sent too many verification codes to this email recently.",
        "Don't request another code. Use the newest code already in your inbox, or wait a while (it can take up to a day) before asking again."
    )
    data class General(val detail: String) : AcError(
        detail,
        "Retry. If it keeps failing, open Diagnostics and export the log."
    )
}
