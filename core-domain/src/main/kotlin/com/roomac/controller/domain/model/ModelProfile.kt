package com.roomac.controller.domain.model

/**
 * A published appliance profile: which attributes a Haier product declares. Keyed by the uPlusId the
 * appliance announces about itself on the LAN discovery channel, so it is chosen by the unit's own
 * identity rather than by anything the user typed.
 */
data class ModelProfile(
    val uPlusId: String,
    val name: String,
    /** Operation modes the profile lists. Heat is additionally gated on the unit's own report. */
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
    val evidence: String
)

object ModelProfiles {

    /** The exact product this app targets. */
    const val TARGET_MODEL = "HSU-20HFTEX/013WUSDC(OW)-T3"

    /**
     * Features a retailer listing gives for [TARGET_MODEL]. These are a starting point, not proof the
     * local protocol exposes them; the dashboard offers a control only when the profile below and the
     * unit's report both support it.
     */
    val TARGET_LISTING_FEATURES = listOf(
        "Cooling", "Heating", "Dry mode", "Vertical auto swing", "Horizontal auto swing",
        "Wi-Fi", "Self-clean", "Turbo", "ECO"
    )

    /**
     * The shared wall-split profile ("共享空调_V2D18S_0D07", deviceType 0201203a). The target AC
     * announces this exact uPlusId on UDISCOVERY (observed 2026-11-11 from 192.168.18.68). The same
     * uPlusId is haismart-local's hardware-verified "classic" reference unit, so its attribute list
     * and group-set positions are the best-evidenced ones available. The profile itself lists modes
     * 0/1/2/6 (no heat), because it was published for a cooling-only unit; heat is offered only when
     * the appliance's own report says it has a heat pump.
     */
    val SHARED_WALL_SPLIT = ModelProfile(
        uPlusId = "2008610800820324021200118012560000000000000000000000000000000040",
        name = "Haier wall split (shared profile 0201203a)",
        modes = listOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.HEAT, AcMode.FAN),
        fanSpeeds = listOf(FanSpeed.AUTO, FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH),
        minTemperatureC = 16.0,
        maxTemperatureC = 30.0,
        temperatureStepC = 1.0,
        verticalSwing = true,
        horizontalSwing = true,
        features = setOf(AcFeature.TURBO, AcFeature.QUIET, AcFeature.SLEEP, AcFeature.HEALTH, AcFeature.DISPLAY),
        ecoLevels = listOf(EcoLevel.OFF, EcoLevel.LEVEL_1, EcoLevel.LEVEL_2, EcoLevel.LEVEL_3),
        selfClean = true,
        evidence = "Published device configuration for uPlusId 2008…0040 (via haismart-local, MIT); " +
            "uPlusId observed from the target AC on the LAN."
    )

    private val byUplusId = listOf(SHARED_WALL_SPLIT).associateBy { it.uPlusId.lowercase() }

    fun forUplusId(uPlusId: String?): ModelProfile? =
        uPlusId?.lowercase()?.let { byUplusId[it] }

    /** Upper-cases and strips whitespace so sticker spellings compare equal. */
    fun normalizeModel(raw: String): String =
        raw.trim().uppercase().replace(Regex("\\s+"), "")

    fun recognizeModel(raw: String): ModelRecognition {
        val model = normalizeModel(raw)
        return when {
            model.isEmpty() -> ModelRecognition.Empty
            model == TARGET_MODEL -> ModelRecognition.ExactTarget
            // Same indoor unit, different suffix (colour / market / outdoor pairing)
            model.startsWith("HSU-20HFTEX") -> ModelRecognition.TargetFamily(model)
            model.matches(Regex("^(HSU|HS|HSW|HCFI|HW|AS|HSB)-?[0-9A-Z/()\\-]+$")) ->
                ModelRecognition.OtherHaier(model)
            else -> ModelRecognition.Unrecognized(model)
        }
    }
}

sealed class ModelRecognition {
    object Empty : ModelRecognition()
    object ExactTarget : ModelRecognition()
    data class TargetFamily(val model: String) : ModelRecognition()
    data class OtherHaier(val model: String) : ModelRecognition()
    data class Unrecognized(val model: String) : ModelRecognition()

    val isAcceptable: Boolean get() = this is ExactTarget || this is TargetFamily || this is OtherHaier

    val message: String
        get() = when (this) {
            Empty -> "Enter the model number from the indoor unit's rating label."
            ExactTarget -> "Exact target model recognised."
            is TargetFamily -> "Same indoor unit family as ${ModelProfiles.TARGET_MODEL}; variant $model."
            is OtherHaier -> "Haier model $model. Not the target model, so controls depend on what the unit reports."
            is Unrecognized -> "\"$model\" does not look like a Haier air-conditioner model number."
        }
}

object CapabilityResolver {

    /**
     * Combines the profile (what the product declares) with the latest report (what this unit says
     * about itself). Without a report nothing is controllable, because writes are group-sets seeded
     * from the unit's own state.
     */
    fun resolve(profile: ModelProfile?, status: AcStatus?): AcCapabilities? {
        if (status == null) return null
        val writable = status.writable
        if (profile == null) {
            // Unknown profile: only the core block that every classic report carries at fixed offsets.
            return AcCapabilities(
                modes = listOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.FAN) +
                    if (status.heatCapable == true) listOf(AcMode.HEAT) else emptyList(),
                fanSpeeds = listOf(FanSpeed.AUTO, FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH),
                minTemperatureC = 16.0, maxTemperatureC = 30.0, temperatureStepC = 1.0,
                verticalSwing = true, horizontalSwing = false,
                features = emptySet(), ecoLevels = emptyList(), selfClean = false,
                writable = writable, autoFanInFanMode = false
            )
        }
        val modes = profile.modes.filter { it != AcMode.HEAT || status.heatCapable == true }
        return AcCapabilities(
            modes = modes,
            fanSpeeds = profile.fanSpeeds,
            minTemperatureC = profile.minTemperatureC,
            maxTemperatureC = profile.maxTemperatureC,
            temperatureStepC = profile.temperatureStepC,
            verticalSwing = profile.verticalSwing,
            horizontalSwing = profile.horizontalSwing && status.horizontalSwing != null,
            features = profile.features.filter { status.features.containsKey(it) }.toSet(),
            ecoLevels = if (status.ecoLevel != null) profile.ecoLevels else emptyList(),
            selfClean = profile.selfClean && status.selfCleaning != null,
            writable = writable,
            autoFanInFanMode = false
        )
    }

    /**
     * Validates a command against capabilities and the current state before anything is sent.
     * Returns null when the command may be sent, otherwise the error to show.
     */
    fun validate(command: AcCommand, caps: AcCapabilities, status: AcStatus): AcError? {
        if (!caps.writable) return AcError.UnsupportedLayout(status.reportLength)
        return when (command) {
            is AcCommand.SetPower -> null
            is AcCommand.SetMode ->
                if (command.mode in caps.modes) null else AcError.NotSupported("${command.mode.label} mode")
            is AcCommand.SetTargetTemperature -> {
                val t = command.celsius
                val stepOk = ((t - caps.minTemperatureC) / caps.temperatureStepC).let { kotlin.math.abs(it - kotlin.math.round(it)) < 1e-6 }
                when {
                    t < caps.minTemperatureC || t > caps.maxTemperatureC ->
                        AcError.InvalidValue("${formatTemp(t)} is outside ${formatTemp(caps.minTemperatureC)}–${formatTemp(caps.maxTemperatureC)}")
                    !stepOk -> AcError.InvalidValue("${formatTemp(t)} is not a multiple of ${caps.temperatureStepC}°C")
                    else -> null
                }
            }
            is AcCommand.SetFanSpeed -> when {
                command.speed !in caps.fanSpeeds -> AcError.NotSupported("Fan speed ${command.speed.label}")
                // Confirmed on Haier hardware: the AC drops a group-set combining fan-only with fan=auto.
                !caps.autoFanInFanMode && command.speed == FanSpeed.AUTO && status.mode == AcMode.FAN ->
                    AcError.InvalidValue("Auto fan is not available in Fan mode")
                else -> null
            }
            is AcCommand.SetVerticalSwing -> if (caps.verticalSwing) null else AcError.NotSupported("Up/down swing")
            is AcCommand.SetHorizontalSwing -> if (caps.horizontalSwing) null else AcError.NotSupported("Left/right swing")
            is AcCommand.SetFeature -> if (command.feature in caps.features) null else AcError.NotSupported(command.feature.label)
            is AcCommand.SetEcoLevel -> if (command.level in caps.ecoLevels) null else AcError.NotSupported("ECO")
            AcCommand.StartSelfClean -> when {
                !caps.selfClean -> AcError.NotSupported("Self-clean")
                // The model's own modifiers lock self-clean while off, in auto mode or sleeping.
                !status.power -> AcError.InvalidValue("turn the AC on before starting self-clean")
                status.mode == AcMode.AUTO -> AcError.InvalidValue("self-clean cannot start in Auto mode")
                status.features[AcFeature.SLEEP] == true -> AcError.InvalidValue("self-clean cannot start while Sleep is on")
                status.selfCleaning == true -> AcError.InvalidValue("a self-clean cycle is already running")
                else -> null
            }
        }
    }
}
