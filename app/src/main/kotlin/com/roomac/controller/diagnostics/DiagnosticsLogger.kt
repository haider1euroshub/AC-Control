package com.roomac.controller.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class LogEntry(
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    val formattedTimestamp: String by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampEpochMs))
    }

    override fun toString(): String {
        return "[$formattedTimestamp] [${level.name}] [$tag] $message"
    }
}

object DiagnosticsLogger {

    private const val MAX_LOG_ENTRIES = 200

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Thorough regex patterns for secrets redaction (keys, passwords, tokens, in assignment or JSON forms)
    private val passwordPattern = Regex("(?i)\\b(password|token|key|secret|localKeyHex|pskHex|refreshToken|accessToken|accountToken|uhomeAccessToken|sesame|localKey)[\"']?\\s*[:=]\\s*([\"']?[^\\s,\"'}]+[\"']?)")
    private val hexKeyPattern = Regex("(?i)\\b([0-9a-f]{32}|[0-9a-f]{64})\\b")

    fun log(level: LogLevel, tag: String, message: String) {
        val sanitized = redactSecrets(message)
        val entry = LogEntry(level = level, tag = tag, message = sanitized)
        synchronized(this) {
            val current = _logs.value.toMutableList()
            if (current.size >= MAX_LOG_ENTRIES) {
                current.removeAt(0)
            }
            current.add(entry)
            _logs.value = current
        }

        // Forward to Android Logcat
        try {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, sanitized)
                LogLevel.INFO -> Log.i(tag, sanitized)
                LogLevel.WARN -> Log.w(tag, sanitized)
                LogLevel.ERROR -> Log.e(tag, sanitized)
            }
        } catch (_: Throwable) {
            // JVM test environment where android.util.Log may not be mocked
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)


    fun redactSecrets(input: String): String {
        var result = input
        // 1. Redact assignment/json secrets completely
        result = passwordPattern.replace(result) { match ->
            val keyWord = match.groupValues[1]
            "$keyWord=[REDACTED]"
        }
        // 2. Redact standalone 32 or 64 char hex keys completely (no partial reveal)
        result = hexKeyPattern.replace(result) {
            "[REDACTED_HEX_KEY]"
        }
        return result
    }

    fun getExportableLogText(): String {
        val list = _logs.value
        if (list.isEmpty()) {
            return "Diagnostics log is empty."
        }
        return list.joinToString("\n") { it.toString() }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
