package com.roomac.controller.protocol

import com.roomac.controller.domain.model.*

/**
 * Pluggable frame codec used by [DriverConnectionManager] to frame raw byte streams.
 */
interface FrameCodec {
    /**
     * Checks if the accumulated buffer contains at least one complete frame.
     * Returns the size of the first complete frame in bytes, or 0 if more bytes are needed,
     * or -1 if the buffer starts with invalid garbage that should be skipped.
     */
    fun findFrameBoundary(buffer: ByteArray, offset: Int, length: Int): FrameBoundaryResult

    /**
     * Identifies if a complete frame is an ACK, a full status update, or unknown.
     */
    fun classifyFrame(frame: ByteArray): FrameType

    /**
     * Parses a status frame into an AcState.
     */
    fun parseState(frame: ByteArray): AcState?
}

sealed class FrameBoundaryResult {
    data class CompleteFrame(val frameLength: Int) : FrameBoundaryResult()
    object NeedMoreBytes : FrameBoundaryResult()
    data class DiscardGarbage(val bytesToDiscard: Int) : FrameBoundaryResult()
}

enum class FrameType {
    STATUS_RESPONSE,
    ACK,
    UNSOLICITED_STATUS,
    UNKNOWN
}

