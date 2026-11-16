package com.doinn.plugins.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadRequeueTest {
    @Test fun delayStartsAtFiveSecondsAndDoubles() {
        assertEquals(listOf(5L, 10L, 20L, 40L, 80L, 160L), (0..5).map { requeueDelaySeconds(it) })
    }

    @Test fun delayIsCappedAtFiveMinutes() {
        assertEquals(300L, requeueDelaySeconds(6))
        assertEquals(300L, requeueDelaySeconds(7))
        assertEquals(300L, requeueDelaySeconds(10_000))
        assertEquals(300L, requeueDelaySeconds(Int.MAX_VALUE))
    }

    @Test fun negativeCountIsTreatedAsZero() {
        assertEquals(5L, requeueDelaySeconds(-3))
    }

    @Test fun firstRunThatMovesNoByteStillWaitsFiveSeconds() {
        assertEquals(RequeuePlan(idleWaits = 1, delaySeconds = 5), planRequeue(0, 100, 100))
    }

    @Test fun runThatAdvancesResetsTheCountAndTheDelay() {
        assertEquals(RequeuePlan(idleWaits = 0, delaySeconds = 5), planRequeue(6, 100, 101))
    }

    @Test fun runThatEndsBelowItsStartCountsAsNoProgress() {
        assertEquals(RequeuePlan(idleWaits = 3, delaySeconds = 20), planRequeue(2, 100, 80))
    }

    @Test fun sixIdleRunsThenOneThatMovesABytePlayOutTheSchedule() {
        var idle = 0
        val delays = mutableListOf<Long>()
        repeat(6) {
            val plan = planRequeue(idle, 100, 100)
            delays += plan.delaySeconds
            idle = plan.idleWaits
        }
        assertEquals(listOf(5L, 10L, 20L, 40L, 80L, 160L), delays)
        assertEquals(6, idle)
        assertEquals(RequeuePlan(0, 5), planRequeue(idle, 100, 4_096))
    }

    @Test fun theSeventhIdleRunHitsTheCap() {
        assertEquals(RequeuePlan(idleWaits = 7, delaySeconds = 300), planRequeue(6, 100, 100))
    }
}
