package com.roomac.controller

import com.roomac.controller.diagnostics.DiagnosticsLogger
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLoggerTest {

    @After
    fun tearDown() {
        DiagnosticsLogger.clear()
    }

    @Test
    fun thirtyTwoCharHexKeyIsCompletelyRedacted() {
        DiagnosticsLogger.d("TestTag", "Connecting with localKeyHex=0123456789abcdef0123456789abcdef")
        val exported = DiagnosticsLogger.getExportableLogText()
        assertFalse(exported.contains("0123456789abcdef0123456789abcdef"))
        assertFalse(exported.contains("0123")) // No partial reveal!
        assertTrue(exported.contains("[REDACTED"))
    }

    @Test
    fun shortHexStringIsNotRedacted() {
        DiagnosticsLogger.d("TestTag", "Packet header 55aa")
        val exported = DiagnosticsLogger.getExportableLogText()
        assertTrue(exported.contains("55aa"))
    }

    @Test
    fun passwordWithColonOrEqualsIsRedacted() {
        DiagnosticsLogger.d("TestTag", "User secret: password = supersecretpass123")
        val exported = DiagnosticsLogger.getExportableLogText()
        assertFalse(exported.contains("supersecretpass123"))
        assertTrue(exported.contains("password=[REDACTED]"))
    }

    @Test
    fun ringBufferRetainsMaximumEntries() {
        DiagnosticsLogger.clear()
        repeat(210) { i ->
            DiagnosticsLogger.d("TestTag", "Log message $i")
        }
        val exported = DiagnosticsLogger.getExportableLogText()
        assertFalse(exported.contains("Log message 0\n"))
        assertTrue(exported.contains("Log message 209"))
    }
}
