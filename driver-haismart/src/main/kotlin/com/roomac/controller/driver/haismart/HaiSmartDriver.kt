package com.roomac.controller.driver.haismart

import com.roomac.controller.domain.model.*
import com.roomac.controller.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket

class HaiSmartDriver(
    private val socketFactory: () -> Socket = { Socket() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AcDriver {

    override val id: String = "haier-haismart"
    override val displayName: String = "Haier Haismart (U+ / AES-128 / Port 56800)"
    override val verificationStatus: DriverVerificationStatus = DriverVerificationStatus.UNVERIFIED_FROM_DOCUMENTATION
    override val description: String = "AES-128 encrypted local TCP port 56800 protocol used by Haier Haismart appliances in SE Asia / Pakistan (reimplemented from enapt/haismart-local). Requires a 16-byte local key."

    private var keyBytes: ByteArray? = null

    private val connectionManager = DriverConnectionManager(
        tag = "HaiSmartDriver",
        codec = HaiSmartFrameCodec(keyBytesProvider = { keyBytes }),
        socketFactory = socketFactory,
        ioDispatcher = ioDispatcher
    )

    override val connectionStatus: StateFlow<ConnectionStatus> = connectionManager.connectionStatus
    override val stateFlow: StateFlow<AcState> = connectionManager.stateFlow

    private val _capabilities = MutableStateFlow(
        AcCapabilities(
            supportsHeating = false,
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

        val parsedKey = try {
            if (config.localKeyHex.isBlank()) {
                null
            } else {
                val bytes = HaiSmartCipher.hexToBytes(config.localKeyHex)
                if (bytes.size != 16) null else bytes
            }
        } catch (_: Exception) {
            null
        }

        if (parsedKey == null) {
            val err = AcError.AuthOrKeyRejected
            return Result.failure(Exception("Local 16-byte hex key is missing or invalid: ${err.userMessage}"))
        }
        keyBytes = parsedKey

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
        val key = keyBytes ?: return CommandResult.Failed(AcError.AuthOrKeyRejected)

        val jsonPayload = if (command is AcCommand.RefreshState) {
            HaiSmartPacket.buildStatusQueryPayload()
        } else {
            val cur = stateFlow.value
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

            HaiSmartPacket.buildControlPayload(
                power = targetPower,
                mode = targetMode,
                targetTemperature = targetTemp,
                verticalSwing = targetVert,
                horizontalSwing = targetHoriz
            )
        }

        val packet = HaiSmartPacket.buildPacket(jsonPayload, key)
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

            val key = try {
                if (config.localKeyHex.isNotBlank()) HaiSmartCipher.hexToBytes(config.localKeyHex) else null
            } catch (_: Exception) { null }

            if (key != null && key.size == 16) {
                val queryPacket = HaiSmartPacket.buildPacket(HaiSmartPacket.buildStatusQueryPayload(), key)
                socket.getOutputStream().write(queryPacket)
                socket.getOutputStream().flush()
            }

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
