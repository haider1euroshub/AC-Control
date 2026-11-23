package com.roomac.controller.netscan

/** MAC address helpers. */
object Mac {
    private val HEX = Regex("^[0-9A-F]{12}$")

    /** "aa-bb-cc-dd-ee-ff", "aabb.ccdd.eeff" etc. to "AA:BB:CC:DD:EE:FF"; null if not a usable unicast MAC. */
    fun normalize(raw: String?): String? {
        val hex = raw?.uppercase()?.filter { it.isLetterOrDigit() } ?: return null
        if (!HEX.matches(hex) || hex == "000000000000" || hex == "FFFFFFFFFFFF") return null
        return hex.chunked(2).joinToString(":")
    }

    /**
     * Locally administered (bit 1 of the first octet): phones and laptops use these as per-network
     * "private" addresses, so the prefix identifies no manufacturer.
     */
    fun isRandomized(mac: String): Boolean = (mac.substring(0, 2).toInt(16) and 0x02) != 0

    fun prefix(mac: String): String = mac.substring(0, 8)
}

data class OuiVendor(val name: String, /** Set only when the maker sells essentially one kind of networked device. */ val typeHint: DeviceType? = null)

/**
 * MAC prefix (OUI) to manufacturer, used only as a best-effort label: unknown prefixes give no vendor.
 * [DEFAULT] is built from [OuiData], which is generated from the IEEE MA-L registry by
 * tools/netscan/generate-oui-table.ps1 and holds only the brands that script selects.
 */
class OuiTable(private val entries: Map<String, OuiVendor>) {

    val size: Int get() = entries.size

    fun lookup(mac: String?): OuiVendor? {
        val m = Mac.normalize(mac) ?: return null
        if (Mac.isRandomized(m)) return null
        return entries[Mac.prefix(m)]
    }

    companion object {
        /** "B827EB DCA632" (space-separated 6-hex OUIs) to "B8:27:EB" keys. */
        fun parsePrefixes(packed: String): List<String> =
            packed.split(' ').filter { it.length == 6 }.map { it.uppercase().chunked(2).joinToString(":") }

        fun fromGenerated(vendors: List<OuiData.Vendor>): OuiTable = OuiTable(
            vendors.flatMap { v -> parsePrefixes(v.prefixes).map { it to OuiVendor(v.name, v.typeHint) } }.toMap()
        )

        val DEFAULT: OuiTable by lazy { fromGenerated(OuiData.VENDORS) }
    }
}
