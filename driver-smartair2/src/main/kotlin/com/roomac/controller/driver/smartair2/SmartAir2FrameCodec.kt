package com.roomac.controller.driver.smartair2

import com.roomac.controller.domain.model.AcState
import com.roomac.controller.protocol.FrameBoundaryResult
import com.roomac.controller.protocol.FrameCodec
import com.roomac.controller.protocol.FrameType

/**
 * FrameCodec for SmartAir2 binary protocol.
 * Validates length-based boundaries and delimiters (0xFF 0xFF).
 */
class SmartAir2FrameCodec : FrameCodec {

    override fun findFrameBoundary(buffer: ByteArray, offset: Int, length: Int): FrameBoundaryResult {
        if (length < 2) return FrameBoundaryResult.NeedMoreBytes

        // Seek 0xFF 0xFF header
        var headerIndex = -1
        for (i in offset until (offset + length - 1)) {
            if (buffer[i] == 0xFF.toByte() && buffer[i + 1] == 0xFF.toByte()) {
                headerIndex = i
                break
            }
        }

        if (headerIndex == -1) {
            // No header found in buffer; discard all but the last byte (which might be the first 0xFF)
            return FrameBoundaryResult.DiscardGarbage(length - 1)
        }

        if (headerIndex > offset) {
            // Discard bytes preceding header
            return FrameBoundaryResult.DiscardGarbage(headerIndex - offset)
        }

        // We have header at offset. Check if length bytes are available (index 2, 3)
        if (length < 4) return FrameBoundaryResult.NeedMoreBytes

        val payloadLength = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
        val totalExpectedFrameLength = 2 + payloadLength + 1 // Header (2) + payloadLength + Checksum (1)

        if (totalExpectedFrameLength > 512 || payloadLength < 2) {
            // Corrupt length field, discard header and resume seeking
            return FrameBoundaryResult.DiscardGarbage(2)
        }

        if (length < totalExpectedFrameLength) {
            return FrameBoundaryResult.NeedMoreBytes
        }

        return FrameBoundaryResult.CompleteFrame(totalExpectedFrameLength)
    }

    override fun classifyFrame(frame: ByteArray): FrameType {
        if (frame.size < 10) return FrameType.UNKNOWN
        val cmd = ((frame[8].toInt() and 0xFF) shl 8) or (frame[9].toInt() and 0xFF)
        return when (cmd) {
            0x0001 -> FrameType.STATUS_RESPONSE
            0x000C -> FrameType.ACK
            else -> FrameType.STATUS_RESPONSE
        }
    }

    override fun parseState(frame: ByteArray): AcState? {
        return SmartAir2Layout.parseStatusPacket(frame)
    }
}

