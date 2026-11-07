package com.roomac.controller.driver.smartair2

import com.roomac.controller.domain.model.*
import com.roomac.controller.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket

class SmartAir2Driver(
    private val socketFactory: () -> Socket = { Socket() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AcDriver {

    override val id: String = "haier-smartair2"
    override val displayName: String = "Haier SmartAir2 (Binary / Local Port 56800)"
    override val verificationStatus: DriverVerificationStatus = DriverVerificationStatus.UNVERIFIED_FROM_DOCUMENTATION
    override val description: String = "Unencrypted local TCP port 56800 protocol used by legacy Haier SmartAir2 modules (reimplemented from bstuff/haier-ac-remote and paveldn/haier-esphome)."

    private val connectionManager = DriverConnectionManager(
        tag = "SmartAir2Driver",
        codec = SmartAir2FrameCodec(),
        socketFactory = socketFactory,
        ioDispatcher = ioDispatcher
    )

    override val connectionStatus: StateFlow<ConnectionStatus> = connectionManager.connectionStatus
    override val stateFlow: StateFlow<AcState> = connectionManager.stateFlow

    // Honest capabilities: default to conservative assumptions until confirmed
    private val _capabilities = MutableStateFlow(
        AcCapabilities(
            supportsHeating = false, // Conservative: requires owner confirmation
            minTemperature = 16.0,
            maxTemperature = 30.0,
            temperatureStep = 1.0,
            temperatureUnit = TemperatureUnit.CELSIUS,
            supportedVerticalSwings = setOf(SwingPosition.OFF, SwingPosition.OSCILLATE),
            supportedHorizontalSwings = setOf(SwingPosition.OFF),
            source = CapabilitySource.ASSUMED_DEFAULT,
            areCapabilitiesKnown = false
        )
    )
    override val capabilitiesFlow: StateFlow<AcCapabilities> = _capabilities.asStateFlow()

    private var activeConfig: DeviceConfig? = null

    override fun setDiagnosticsSink(sink: DiagnosticsSink) {
        connectionManager.setDiagnosticsSink(sink)
    }

    override suspend fun connect(config: DeviceConfig): Result<Unit> {
        activeConfig = config
        // Apply owner-asserted bring-up baseline if enabled
        val baseline = config.ownerBaseline
        if (baseline != null && baseline.isEnabled) {
            connectionManager.updateState { current ->
                current.copy(
                    power = ValueWithProvenance.ownerAsserted(baseline.power),
                    mode = ValueWithProvenance.ownerAsserted(baseline.mode),
                    targetTemperature = ValueWithProvenance.ownerAsserted(baseline.targetTemperature),
                    swing = ValueWithProvenance.ownerAsserted(SwingState(vertical = baseline.verticalSwing, horizontal = baseline.horizontalSwing))
                )
            }
        }

        val res = connectionManager.connect(config)
        if (res.isSuccess) {
            // Send initial poll without blocking
            CoroutineScope(ioDispatcher).launch {
                executeCommand(AcCommand.RefreshState)
            }
        }
        return res
    }

    override suspend fun disconnect() {
        connectionManager.disconnect()
    }

    override suspend fun executeCommand(command: AcCommand): CommandResult {
        if (command is AcCommand.RefreshState) {
            val queryPacket = SmartAir2Layout.buildStatusQueryPacket()
            return connectionManager.sendPacketAndAwaitResponse(queryPacket, command)
        }

        val cur = stateFlow.value

        // COMMAND SAFETY GATE: Ensure required fields are known before sending full control frame
        val targetPower = when (command) {
            is AcCommand.SetPower -> command.power
            else -> cur.power.value ?: return CommandResult.Failed(
                AcError.CommandBlocked("AC power state is unknown; cannot alter parameters")
            )
        }

        val targetMode = when (command) {
            is AcCommand.SetMode -> command.mode
            else -> cur.mode.value ?: AcMode.COOL
        }

        val targetTemp = when (command) {
            is AcCommand.SetTargetTemperature -> command.target
            else -> cur.targetTemperature.value ?: 24.0
        }

        val targetVert = when (command) {
            is AcCommand.SetVerticalSwing -> command.position
            else -> cur.swing.value?.vertical ?: SwingPosition.OFF
        }

        val targetHoriz = when (command) {
            is AcCommand.SetHorizontalSwing -> command.position
            else -> cur.swing.value?.horizontal ?: SwingPosition.OFF
        }

        val packet = SmartAir2Layout.buildControlPacket(
            power = targetPower,
            mode = targetMode,
            targetTemperature = targetTemp,
            verticalSwing = targetVert,
            horizontalSwing = targetHoriz
        )

        return connectionManager.sendPacketAndAwaitResponse(packet, command)
    }

    override suspend fun testConnection(config: DeviceConfig): Result<String> = withContext(ioDispatcher) {
        val probe = probeConnection(config, 3000L)
        when (probe) {
            is ProbeResult.ConnectedResponded -> Result.success("Connected on port ${config.port} and received ${probe.byteCount} bytes (${probe.rawHex}).")
            is ProbeResult.ConnectedSilent -> Result.success("Connected on port ${config.port}, but AC remained silent within ${probe.timeoutMs}ms.")
            is ProbeResult.RefusedOrUnreachable -> Result.failure(Exception(probe.error))
        }
    }

    override suspend fun probeConnection(config: DeviceConfig, timeoutMs: Long): ProbeResult = withContext(ioDispatcher) {
        var socket: Socket? = null
        try {
            socket = socketFactory()
            socket.connect(InetSocketAddress(config.host, config.port), timeoutMs.toInt().coerceAtMost(3000))
            socket.soTimeout = timeoutMs.toInt()

            // Send passive status query
            val query = SmartAir2Layout.buildStatusQueryPacket()
            val out = socket.getOutputStream()
            out.write(query)
            out.flush()

            val buf = ByteArray(256)
            val read = socket.getInputStream().read(buf)
            if (read > 0) {
                val hex = buf.copyOf(read).joinToString("") { "%02x".format(it) }
                ProbeResult.ConnectedResponded(config.host, config.port, hex, read)
            } else {
                ProbeResult.ConnectedSilent(config.host, config.port, timeoutMs)
            }
        } catch (e: Exception) {
            ProbeResult.RefusedOrUnreachable("Could not reach ${config.host}:${config.port}: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }
}
