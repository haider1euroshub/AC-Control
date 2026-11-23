package com.roomac.controller.domain.model

/**
 * A saved air conditioner. Identified by [deviceId] (the Wi-Fi module MAC the appliance announces and
 * the protocol handshake is bound to), never by IP address: [lastKnownHost] is only a hint that is
 * re-discovered whenever it stops answering.
 *
 * The local key is not part of this record; it lives in Keystore-encrypted storage keyed by [deviceId].
 */
data class PairedDevice(
    val deviceId: String,
    val name: String,
    /** Room the user assigned (e.g. "Bedroom"); "" when not set. */
    val room: String = "",
    /** Which protocol adapter talks to this unit (see ProtocolIds). Records saved before 2.1 are Haier uSS. */
    val protocolId: String = ProtocolIds.HAIER_USS,
    /** Manufacturer as identified during discovery (brand id from [AcBrands]), or "" when unknown. */
    val brand: String = "",
    val uPlusId: String = "",
    val modelNumber: String = "",
    val serialNumber: String = "",
    val productCode: String = "",
    val lastKnownHost: String = "",
    val port: Int = DEFAULT_USS_PORT,
    val firmware: String = "",
    /** Local-key version the AC reported when the key was last verified; 0 when never verified. */
    val keyVersion: Int = 0,
    val hasLocalKey: Boolean = false,
    /** Commands this device has confirmed by read-back at least once, by [AcCommand] kind. */
    val verifiedControls: Set<String> = emptySet(),
    val addedAtEpochMs: Long = 0L
) {
    companion object {
        const val DEFAULT_USS_PORT = 56800

        /** Stable kind name used to record which controls were verified on this unit. */
        fun controlKind(command: AcCommand): String = when (command) {
            is AcCommand.SetPower -> "power"
            is AcCommand.SetMode -> "mode:${command.mode.name}"
            is AcCommand.SetTargetTemperature -> "temperature"
            is AcCommand.SetFanSpeed -> "fan:${command.speed.name}"
            is AcCommand.SetVerticalSwing -> "swing_vertical"
            is AcCommand.SetHorizontalSwing -> "swing_horizontal"
            is AcCommand.SetFeature -> "feature:${command.feature.name}"
            is AcCommand.SetEcoLevel -> "eco"
            AcCommand.StartSelfClean -> "self_clean"
        }
    }
}
