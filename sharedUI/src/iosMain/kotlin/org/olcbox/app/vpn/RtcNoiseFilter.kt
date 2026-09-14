package org.olcbox.app.vpn

import kotlin.time.TimeSource
import platform.Foundation.NSLock

/**
 * Folds olcRTC's debug floods into counts. A congested video channel prints a
 * line per duplicated SRTP packet and pion prints one per failed ICE send, which
 * in a real test filled a 3000-line log in 46 minutes and would push days of
 * history off the disk. Each noisy kind is written once, then counted for
 * [windowMs]; when the window closes the count is written as one line, so the
 * log still shows when and how hard it happened.
 *
 * Everything else passes through untouched.
 */
class RtcNoiseFilter(
    private val windowMs: Long = 30_000,
    private val now: () -> Long = monotonicMillis()
) {
    private class Window(val openedAt: Long, var suppressed: Int = 0)

    private val lock = NSLock()
    private val windows = mutableMapOf<String, Window>()

    /** Lines to write for [line]: closed-window counts first, then the line if it is not folded. */
    fun filter(line: String): List<String> {
        lock.lock()
        try {
            val out = closeExpired(now())
            val key = keyOf(line)
            when {
                key == null -> out += line
                windows[key] == null -> {
                    windows[key] = Window(now())
                    out += line
                }
                else -> windows.getValue(key).suppressed++
            }
            return out
        } finally {
            lock.unlock()
        }
    }

    /** Counts of windows that closed with nothing new arriving, e.g. for a periodic heartbeat. */
    fun flush(): List<String> {
        lock.lock()
        try {
            return closeExpired(now())
        } finally {
            lock.unlock()
        }
    }

    private fun closeExpired(time: Long): MutableList<String> {
        val out = mutableListOf<String>()
        val expired = windows.filterValues { time - it.openedAt >= windowMs }
        for ((key, window) in expired) {
            windows.remove(key)
            if (window.suppressed > 0) {
                out += "(+${window.suppressed} more \"$key\" in ${(time - window.openedAt) / 1000}s)"
            }
        }
        return out
    }

    companion object {
        private fun monotonicMillis(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }

        /**
         * The kind a noisy line belongs to, or null for a line that must be kept.
         * The failure reason is part of the kind: "can't assign requested address"
         * after a network change and "broken pipe" on teardown mean different things.
         */
        fun keyOf(line: String): String? = when {
            "duplicated packet" in line -> "srtp duplicated packet"
            "Failed to ping without candidate pairs" in line -> "ice ping without candidate pairs"
            "Failed to send packet" in line -> "ice send failed: ${reason(line)}"
            "Failed to read from candidate" in line -> "ice read failed: ${reason(line)}"
            else -> null
        }

        private fun reason(line: String): String =
            line.substringAfterLast(": ").trimEnd('"', ' ').take(60)
    }
}
