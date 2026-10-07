package com.vscode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerReadinessTest {

    @Test
    fun timesOutWithinConfiguredDeadline() {
        var clock = 100L
        var probes = 0
        val result = awaitServerReadiness(
            timeoutMs = 10L,
            pollIntervalMs = 3L,
            isAlive = { true },
            shouldStop = { false },
            probe = { probes++; false },
            nowMs = { clock },
            sleep = { clock += it }
        )

        assertEquals(ServerReadiness.TIMED_OUT, result)
        assertEquals(110L, clock)
        assertTrue("probe count must remain bounded", probes <= 5)
    }

    @Test
    fun reportsProcessExitWithoutPollingServer() {
        var probes = 0
        val result = awaitServerReadiness(
            timeoutMs = 10_000L,
            pollIntervalMs = 300L,
            isAlive = { false },
            shouldStop = { false },
            probe = { probes++; false },
            nowMs = { 0L },
            sleep = { error("must not sleep") }
        )

        assertEquals(ServerReadiness.PROCESS_EXITED, result)
        assertEquals(0, probes)
    }

    @Test
    fun reportsUserStopBeforeAdditionalProbe() {
        var probes = 0
        val result = awaitServerReadiness(
            timeoutMs = 10_000L,
            pollIntervalMs = 300L,
            isAlive = { true },
            shouldStop = { true },
            probe = { probes++; false },
            nowMs = { 0L },
            sleep = { error("must not sleep") }
        )

        assertEquals(ServerReadiness.STOP_REQUESTED, result)
        assertEquals(0, probes)
    }

    @Test
    fun acceptsReadyEndpointImmediately() {
        var sleeps = 0
        val result = awaitServerReadiness(
            timeoutMs = 10_000L,
            pollIntervalMs = 300L,
            isAlive = { true },
            shouldStop = { false },
            probe = { true },
            nowMs = { 0L },
            sleep = { sleeps++ }
        )

        assertEquals(ServerReadiness.READY, result)
        assertEquals(0, sleeps)
    }
}
