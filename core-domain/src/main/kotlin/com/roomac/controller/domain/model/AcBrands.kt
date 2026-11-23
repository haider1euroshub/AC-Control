package com.roomac.controller.domain.model

/** Stable ids of the protocol adapters. Stored with each device, so they must never change. */
object ProtocolIds {
    const val HAIER_USS = "haier-uss"
    const val GREE_WIFI = "gree-wifi"
    const val MIDEA_V2 = "midea-v2"
    const val MIDEA_V3 = "midea-v3"
}

/** How far an integration has got. Development record only; never shown as a promise to users. */
enum class IntegrationStatus(val label: String) {
    /** Adapter code exists and passes automated tests; not yet confirmed on a physical unit of this brand. */
    IMPLEMENTED_UNVERIFIED("Implemented, not verified on hardware"),
    /** Some steps confirmed on real hardware (e.g. discovery, handshake), control not yet. */
    PARTIALLY_TESTED("Partially tested on hardware"),
    /** Research is underway; no adapter yet. */
    IN_INVESTIGATION("In investigation"),
    NOT_STARTED("Not started")
}

enum class PriorityTier { HIGHEST, INTERNATIONAL, OTHER }

/**
 * One manufacturer the app targets. [protocolIds] lists only adapters that exist in the code; a
 * brand with none is shown as "not supported yet" rather than guessed at. Research leads are kept in
 * docs/COMPATIBILITY_MATRIX.md, where they are labelled as unverified.
 */
data class AcBrand(
    val id: String,
    val displayName: String,
    val tier: PriorityTier,
    /** Market priority from the requirements (1 = highest). */
    val marketPriority: Int,
    /** Position in the integration roadmap (1 = first). */
    val integrationOrder: Int,
    val protocolIds: List<String>,
    val status: IntegrationStatus
)

/**
 * The brand registry. New manufacturers, OEMs and rebrands are added here plus, when an integration
 * exists, an adapter registered under its protocol id; nothing else in the app changes.
 */
object AcBrands {
    val HAIER = AcBrand("haier", "Haier", PriorityTier.HIGHEST, 1, 1, listOf(ProtocolIds.HAIER_USS), IntegrationStatus.PARTIALLY_TESTED)
    val GREE = AcBrand("gree", "Gree", PriorityTier.HIGHEST, 2, 2, listOf(ProtocolIds.GREE_WIFI), IntegrationStatus.IMPLEMENTED_UNVERIFIED)
    val DAWLANCE = AcBrand("dawlance", "Dawlance", PriorityTier.HIGHEST, 3, 6, emptyList(), IntegrationStatus.IN_INVESTIGATION)
    val ORIENT = AcBrand("orient", "Orient", PriorityTier.HIGHEST, 4, 8, emptyList(), IntegrationStatus.IN_INVESTIGATION)
    val PEL = AcBrand("pel", "PEL", PriorityTier.HIGHEST, 5, 9, emptyList(), IntegrationStatus.IN_INVESTIGATION)
    val MIDEA = AcBrand("midea", "Midea", PriorityTier.HIGHEST, 6, 3, listOf(ProtocolIds.MIDEA_V2, ProtocolIds.MIDEA_V3), IntegrationStatus.IMPLEMENTED_UNVERIFIED)
    val AUX = AcBrand("aux", "AUX", PriorityTier.HIGHEST, 7, 4, emptyList(), IntegrationStatus.IN_INVESTIGATION)
    val TCL = AcBrand("tcl", "TCL", PriorityTier.HIGHEST, 8, 5, emptyList(), IntegrationStatus.IN_INVESTIGATION)
    val DAIKIN = AcBrand("daikin", "Daikin", PriorityTier.INTERNATIONAL, 9, 7, emptyList(), IntegrationStatus.IN_INVESTIGATION)
    val PANASONIC = AcBrand("panasonic", "Panasonic", PriorityTier.INTERNATIONAL, 10, 10, emptyList(), IntegrationStatus.NOT_STARTED)
    val LG = AcBrand("lg", "LG", PriorityTier.INTERNATIONAL, 11, 11, emptyList(), IntegrationStatus.NOT_STARTED)
    val SAMSUNG = AcBrand("samsung", "Samsung", PriorityTier.INTERNATIONAL, 12, 12, emptyList(), IntegrationStatus.NOT_STARTED)
    val KENWOOD = AcBrand("kenwood", "Kenwood", PriorityTier.INTERNATIONAL, 13, 13, emptyList(), IntegrationStatus.NOT_STARTED)

    val all: List<AcBrand> = listOf(HAIER, GREE, DAWLANCE, ORIENT, PEL, MIDEA, AUX, TCL, DAIKIN, PANASONIC, LG, SAMSUNG, KENWOOD)

    /** In the order integrations are worked on. */
    val byIntegrationOrder: List<AcBrand> get() = all.sortedBy { it.integrationOrder }

    fun byId(id: String?): AcBrand? = id?.let { key -> all.firstOrNull { it.id == key.trim().lowercase() } }

    /**
     * Maps the brand string a device reports about itself (e.g. Gree modules report `"brand":"gree"`)
     * to a known brand. Unknown strings are kept as reported, so OEM and rebranded units still show
     * the name the unit gave rather than a guess.
     */
    fun displayNameFor(reported: String?): String? {
        val raw = reported?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return byId(raw)?.displayName ?: raw.replaceFirstChar { it.uppercase() }
    }

    fun forProtocol(protocolId: String): List<AcBrand> = all.filter { protocolId in it.protocolIds }
}
