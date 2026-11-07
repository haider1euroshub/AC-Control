package com.roomac.controller.protocol

import com.roomac.controller.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Robust, single-owner connection manager for AC protocol socket transport.
 * Enforces single-reader, single-writer, bounded exponential backoff, structured cancellation,
 * frame assembly (handling split and concatenated frames), and raw diagnostics emission.
 */
class DriverConnectionManager(
    private val tag: String,
    private val codec: FrameCodec,
    private val socketFactory: () -> Socket = { Socket() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 2000
) {
    private var diagnosticsSink: DiagnosticsSink = NoOpDiagnosticsSink

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _state = MutableStateFlow(AcState(isReachable = false))
    val stateFlow: StateFlow<AcState> = _state.asStateFlow()

    private var activeConfig: DeviceConfig? = null
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null

    // Serializes all command transmission to prevent interleaved packets
    private val commandMutex = Mutex()

    // CompletableDeferred for the pending command waiting for its correlated response
    private var pendingResponseDeferred: CompletableDeferred<ByteArray>? = null

    fun setDiagnosticsSink(sink: DiagnosticsSink) {
        diagnosticsSink = sink
    }

    fun updateState(transform: (AcState) -> AcState) {
        _state.value = transform(_state.value)
    }

    suspend fun connect(config: DeviceConfig): Result<Unit> = withContext(ioDispatcher) {
        disconnect()
        activeConfig = config
        _connectionStatus.value = ConnectionStatus.Connecting
        diagnosticsSink.logEvent(tag, "Connecting to ${config.host}:${config.port} (Timeout: ${connectTimeoutMs}ms)")

        try {
            val sock = socketFactory()
            sock.connect(InetSocketAddress(config.host, config.port), connectTimeoutMs)
            sock.soTimeout = readTimeoutMs
            sock.tcpNoDelay = true

            socket = sock
            outputStream = sock.getOutputStream()

            _connectionStatus.value = ConnectionStatus.Connected(config.host, config.port)
            _state.value = _state.value.copy(isReachable = true)
            diagnosticsSink.logEvent(tag, "TCP connection established with ${config.host}:${config.port}")

            startReader(sock.getInputStream())
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticsSink.logError(tag, "Connection failed to ${config.host}:${config.port}: ${e.message}", e)
            val err = AcError.AcUnreachable(config.host)
            _connectionStatus.value = ConnectionStatus.Failed(err)
            _state.value = _state.value.copy(isReachable = false)
            Result.failure(e)
        }
    }

    suspend fun disconnect() = withContext(ioDispatcher) {
        reconnectJob?.cancel()
        reconnectJob = null

        readerJob?.cancel()
        readerJob = null

        pendingResponseDeferred?.cancel()
        pendingResponseDeferred = null

        try {
            socket?.close()
        } catch (_: Exception) {}

        socket = null
        outputStream = null

        _connectionStatus.value = ConnectionStatus.Disconnected
        _state.value = _state.value.copy(isReachable = false)
        diagnosticsSink.logEvent(tag, "Disconnected from socket")
    }

    /**
     * Sends a packet and waits for correlated response under the command mutex lock.
     */
    suspend fun sendPacketAndAwaitResponse(
        packet: ByteArray,
        command: AcCommand,
        responseTimeoutMs: Long = 2500L
    ): CommandResult = commandMutex.withLock {
        withContext(ioDispatcher) {
            val out = outputStream
            val sock = socket
            if (sock == null || out == null || !sock.isConnected || sock.isClosed) {
                val cfg = activeConfig
                if (cfg != null && cfg.isConfigured) {
                    val connRes = connect(cfg)
                    if (connRes.isFailure) {
                        return@withContext CommandResult.Failed(AcError.AcUnreachable(cfg.host))
                    }
                } else {
                    return@withContext CommandResult.Failed(AcError.NoAcConfigured)
                }
            }

            val deferred = CompletableDeferred<ByteArray>()
            pendingResponseDeferred = deferred

            try {
                diagnosticsSink.logRawTx(tag, bytesToHex(packet))
                outputStream?.write(packet)
                outputStream?.flush()

                // Await response with timeout
                val responseFrame = withTimeoutOrNull(responseTimeoutMs) {
                    deferred.await()
                }

                if (responseFrame != null) {
                    diagnosticsSink.logRawRx(tag, bytesToHex(responseFrame))
                    val parsedState = codec.parseState(responseFrame)
                    if (parsedState != null) {
                        _state.value = parsedState
                        return@withContext CommandResult.Confirmed(parsedState)
                    } else {
                        // Frame received (e.g. ACK) but doesn't contain full state
                        val optimistic = applyOptimistic(command, _state.value)
                        _state.value = optimistic
                        return@withContext CommandResult.SentUnconfirmed(optimistic)
                    }
                } else {
                    // Timeout waiting for response frame
                    diagnosticsSink.logEvent(tag, "No confirming response received within ${responseTimeoutMs}ms; marking optimistic")
                    val optimistic = applyOptimistic(command, _state.value)
                    _state.value = optimistic
                    return@withContext CommandResult.SentUnconfirmed(optimistic)
                }
            } catch (e: SocketTimeoutException) {
                diagnosticsSink.logEvent(tag, "Socket read timeout on command; marking SentUnconfirmed")
                val optimistic = applyOptimistic(command, _state.value)
                _state.value = optimistic
                CommandResult.SentUnconfirmed(optimistic)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                diagnosticsSink.logError(tag, "Error during send/await: ${e.message}", e)
                CommandResult.Failed(AcError.GeneralError(e.message ?: "Socket I/O error"))
            } finally {
                pendingResponseDeferred = null
            }
        }
    }

    private fun startReader(inputStream: InputStream) {
        readerJob?.cancel()
        readerJob = CoroutineScope(ioDispatcher).launch {
            val readBuffer = ByteArray(1024)
            var accumulated = ByteArray(0)

            while (isActive) {
                try {
                    val bytesRead = inputStream.read(readBuffer)
                    if (bytesRead == -1) {
                        diagnosticsSink.logEvent(tag, "Remote AC closed connection (EOF)")
                        handlePeerClosed()
                        break
                    }

                    // Append newly read bytes to accumulator
                    val newAccumulated = ByteArray(accumulated.size + bytesRead)
                    accumulated.copyInto(newAccumulated, 0)
                    readBuffer.copyInto(newAccumulated, accumulated.size, 0, bytesRead)
                    accumulated = newAccumulated

                    // Process complete frames from the accumulated buffer
                    while (accumulated.isNotEmpty()) {
                        when (val boundary = codec.findFrameBoundary(accumulated, 0, accumulated.size)) {
                            is FrameBoundaryResult.CompleteFrame -> {
                                val frame = accumulated.copyOfRange(0, boundary.frameLength)
                                accumulated = accumulated.copyOfRange(boundary.frameLength, accumulated.size)
                                handleIncomingFrame(frame)
                            }
                            is FrameBoundaryResult.NeedMoreBytes -> {
                                break // Wait for next read()
                            }
                            is FrameBoundaryResult.DiscardGarbage -> {
                                val discardCount = boundary.bytesToDiscard.coerceAtMost(accumulated.size)
                                diagnosticsSink.logEvent(tag, "Discarding $discardCount bytes of malformed data")
                                accumulated = accumulated.copyOfRange(discardCount, accumulated.size)
                            }
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Normal read timeout in loop; continue listening
                    yield()
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    diagnosticsSink.logError(tag, "Reader exception: ${e.message}", e)
                    handlePeerClosed()
                    break
                }
            }
        }
    }

    private fun handleIncomingFrame(frame: ByteArray) {
        val frameType = codec.classifyFrame(frame)
        diagnosticsSink.logRawRx(tag, "${bytesToHex(frame)} [Type: $frameType]")

        // If a command is waiting for a response, deliver it
        val deferred = pendingResponseDeferred
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(frame)
        }

        // Also parse unsolicited status updates
        if (frameType == FrameType.STATUS_RESPONSE || frameType == FrameType.UNSOLICITED_STATUS) {
            val parsed = codec.parseState(frame)
            if (parsed != null) {
                _state.value = parsed
            }
        }
    }

    private fun handlePeerClosed() {
        _connectionStatus.value = ConnectionStatus.Disconnected
        _state.value = _state.value.copy(isReachable = false)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val cfg = activeConfig ?: return
        if (!cfg.isConfigured) return

        reconnectJob = CoroutineScope(ioDispatcher).launch {
            var delayMs = 2000L
            for (attempt in 1..5) {
                diagnosticsSink.logEvent(tag, "Scheduling reconnect attempt $attempt in ${delayMs}ms")
                delay(delayMs)
                if (!isActive) break

                val res = connect(cfg)
                if (res.isSuccess) {
                    diagnosticsSink.logEvent(tag, "Reconnected successfully on attempt $attempt")
                    break
                }
                delayMs = (delayMs * 2).coerceAtMost(30000L)
            }
        }
    }

    private fun applyOptimistic(command: AcCommand, current: AcState): AcState {
        return when (command) {
            is AcCommand.SetPower -> current.copy(power = ValueWithProvenance.optimistic(command.power))
            is AcCommand.SetMode -> current.copy(mode = ValueWithProvenance.optimistic(command.mode))
            is AcCommand.SetTargetTemperature -> current.copy(targetTemperature = ValueWithProvenance.optimistic(command.target))
            is AcCommand.SetVerticalSwing -> current.copy(swing = ValueWithProvenance.optimistic(current.swing.value?.copy(vertical = command.position) ?: SwingState(vertical = command.position)))
            is AcCommand.SetHorizontalSwing -> current.copy(swing = ValueWithProvenance.optimistic(current.swing.value?.copy(horizontal = command.position) ?: SwingState(horizontal = command.position)))
            is AcCommand.RefreshState -> current
        }
    }

    companion object {
        fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

