package com.roomac.controller.driver.haismart

import com.roomac.controller.domain.model.AcState
import com.roomac.controller.protocol.FrameBoundaryResult
import com.roomac.controller.protocol.FrameCodec
import com.roomac.controller.protocol.FrameType

class HaiSmartFrameCodec(
    private val keyBytesProvider: () -> ByteArray?
) : FrameCodec {

    override fun findFrameBoundary(buffer: ByteArray, offset: Int, length: Int): FrameBoundaryResult {
        if (length < 2) return FrameBoundaryResult.NeedMoreBytes

        var headerIdx = -1
        for (i in offset until (offset + length - 1)) {
            if (buffer[i] == 0x55.toByte() && buffer[i + 1] == 0xAA.toByte()) {
                headerIdx = i
                break
            }
        }

        if (headerIdx == -1) {
            return FrameBoundaryResult.DiscardGarbage(length - 1)
        }
        if (headerIdx > offset) {
            return FrameBoundaryResult.DiscardGarbage(headerIdx - offset)
        }

        if (length < 4) return FrameBoundaryResult.NeedMoreBytes

        val encLen = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
        val totalExpected = 4 + encLen

        if (totalExpected > 1024 || encLen < 16) {
            return FrameBoundaryResult.DiscardGarbage(2)
        }

        if (length < totalExpected) {
            return FrameBoundaryResult.NeedMoreBytes
        }

        return FrameBoundaryResult.CompleteFrame(totalExpected)
    }

    override fun classifyFrame(frame: ByteArray): FrameType {
        return FrameType.STATUS_RESPONSE
    }

    override fun parseState(frame: ByteArray): AcState? {
        val key = keyBytesProvider() ?: return null
        return HaiSmartPacket.parsePacket(frame, key)
    }
}

