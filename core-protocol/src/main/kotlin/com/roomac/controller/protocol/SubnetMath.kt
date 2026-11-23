package com.roomac.controller.protocol

/** IPv4 subnet helpers shared by every discovery implementation. */
object SubnetMath {
    fun toInt(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    fun fromInt(v: Long): String = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    /**
     * Host addresses of the subnet, nearest to [address] first, excluding the network, broadcast and
     * the phone's own address. Subnets larger than [limit] are narrowed to the /22 around the phone.
     */
    fun hosts(address: String, prefixLength: Int, limit: Int): List<String> {
        val ip = toInt(address) ?: return emptyList()
        var prefix = prefixLength.coerceIn(0, 32)
        while (prefix < 30 && (1L shl (32 - prefix)) - 2 > limit) prefix++
        if (prefix >= 31) return emptyList()
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        return ((network + 1) until broadcast).filter { it != ip }
            .sortedBy { kotlin.math.abs(it - ip) }
            .map { fromInt(it) }
    }

    fun broadcastAddress(address: String, prefixLength: Int): String? {
        val ip = toInt(address) ?: return null
        if (prefixLength !in 1..30) return null
        val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        return fromInt((ip and mask) or (mask.inv() and 0xFFFFFFFFL))
    }
}
