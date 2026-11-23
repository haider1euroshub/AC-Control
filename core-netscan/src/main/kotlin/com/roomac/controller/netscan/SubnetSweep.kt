package com.roomac.controller.netscan

import com.roomac.controller.protocol.LocalNetwork
import com.roomac.controller.protocol.SubnetMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

enum class PortState {
    OPEN,
    /** Connection refused: the port is closed, but something at that address answered. */
    CLOSED,
    NO_ANSWER
}

interface PortProber {
    suspend fun probe(ip: String, port: Int, timeoutMs: Int): PortState
}

interface Pinger {
    suspend fun isReachable(ip: String, timeoutMs: Int): Boolean
}

/** Raw text of the kernel neighbour (ARP) table, or null when the OS does not let the app read it. */
fun interface ArpSource {
    fun read(): String?
}

interface HostnameResolver {
    suspend fun lookup(ip: String): String?
}

object ArpTable {
    /**
     * Parses /proc/net/arp ("IP address  HW type  Flags  HW address  Mask  Device") into IP to MAC.
     * Incomplete entries (flags 0x0, all-zero MAC) are skipped.
     */
    fun parse(text: String): Map<String, String> = text.lineSequence().drop(1).mapNotNull { line ->
        val cols = line.trim().split(Regex("\\s+"))
        if (cols.size < 4 || SubnetMath.toInt(cols[0]) == null) return@mapNotNull null
        val flags = cols[2].removePrefix("0x").toIntOrNull(16) ?: 0
        if (flags and 0x2 == 0) return@mapNotNull null
        Mac.normalize(cols[3])?.let { cols[0] to it }
    }.toMap()
}

/**
 * Finds live hosts on the phone's subnet (the /24 around it on larger networks) by pinging and lightly
 * checking a few well-known ports, then adds MAC addresses from the ARP table where the OS allows it
 * and reverse-DNS hostnames where the router provides them.
 */
class SubnetSweep(
    private val prober: PortProber,
    private val pinger: Pinger,
    private val arp: ArpSource,
    private val resolver: HostnameResolver?,
    private val ports: List<Int> = DeviceClassifier.Ports.ALL,
    private val hostLimit: Int = 254,
    private val concurrency: Int = 32,
    private val portTimeoutMs: Int = 400,
    private val pingTimeoutMs: Int = 500,
    private val lookupTimeoutMs: Long = 1_500
) : DiscoverySource {

    override val source = ScanSource.SWEEP

    override suspend fun discover(network: LocalNetwork, sink: ObservationSink) {
        val hosts = SubnetMath.hosts(network.address, network.prefixLength, hostLimit)
        val gate = Semaphore(concurrency)
        val alive = coroutineScope {
            hosts.map { ip ->
                async {
                    gate.withPermit {
                        val states = ports.map { port -> async { port to prober.probe(ip, port, portTimeoutMs) } }
                        val reachable = async { pinger.isReachable(ip, pingTimeoutMs) }
                        val results = states.awaitAll()
                        val open = results.filter { it.second == PortState.OPEN }.map { it.first }.toSet()
                        val up = reachable.await() || results.any { it.second != PortState.NO_ANSWER }
                        if (up) {
                            sink.observe(Observation(ip = ip, source = ScanSource.SWEEP, openPorts = open))
                            ip
                        } else null
                    }
                }
            }.awaitAll().filterNotNull().toMutableSet()
        }

        // The sweep has just filled the neighbour table, so read it now.
        val inSubnet = hosts.toSet()
        val table = arp.read()?.let(ArpTable::parse)
        if (table == null) {
            sink.note("MAC addresses are not available: this Android version does not let apps read the ARP table, so vendors come only from what devices advertise.")
        } else {
            table.filterKeys { it in inSubnet }.forEach { (ip, mac) ->
                alive += ip
                sink.observe(Observation(ip = ip, source = ScanSource.ARP, mac = mac))
            }
        }

        if (resolver != null) coroutineScope {
            alive.map { ip ->
                async {
                    gate.withPermit {
                        withTimeoutOrNull(lookupTimeoutMs) { resolver.lookup(ip) }
                            ?.takeIf { it.isNotBlank() && it != ip }
                            ?.let { sink.observe(Observation(ip = ip, source = ScanSource.SWEEP, hostname = it)) }
                    }
                }
            }.awaitAll()
        }
    }
}

class TcpPortProber(private val io: CoroutineDispatcher = Dispatchers.IO) : PortProber {
    override suspend fun probe(ip: String, port: Int, timeoutMs: Int): PortState = withContext(io) {
        try {
            Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
            PortState.OPEN
        } catch (_: ConnectException) {
            // "Connection refused" is an answer from the host; a timeout or unreachable is not.
            PortState.CLOSED
        } catch (_: Exception) {
            PortState.NO_ANSWER
        }
    }
}

class InetPinger(private val io: CoroutineDispatcher = Dispatchers.IO) : Pinger {
    override suspend fun isReachable(ip: String, timeoutMs: Int): Boolean = withContext(io) {
        try { InetAddress.getByName(ip).isReachable(timeoutMs) } catch (_: Exception) { false }
    }
}

/** Reads /proc/net/arp. Apps targeting Android 10+ are denied it on most devices; that gives null, never a crash. */
class ProcArpSource(private val path: String = "/proc/net/arp") : ArpSource {
    override fun read(): String? = try {
        File(path).readText()
    } catch (_: Exception) {
        null
    }
}

class ReverseDnsResolver(private val io: CoroutineDispatcher = Dispatchers.IO) : HostnameResolver {
    override suspend fun lookup(ip: String): String? = withContext(io) {
        try {
            InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }?.removeSuffix(".")
        } catch (_: Exception) {
            null
        }
    }
}
