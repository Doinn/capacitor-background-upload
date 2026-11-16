package com.doinn.plugins.backgroundupload

data class RequeuePlan(val idleWaits: Int, val delaySeconds: Long)

private const val BASE_DELAY_SECONDS = 5L
private const val MAX_DELAY_SECONDS = 300L
private const val MAX_DOUBLINGS = 6

/**
 * Delay before the replacement of a run that moved no byte: 5 seconds, doubling with every
 * consecutive idle run and capped at 300. The doubling count is bounded before shifting so a huge
 * count cannot overflow.
 */
fun requeueDelaySeconds(consecutiveIdleWaits: Int): Long {
    val doublings = consecutiveIdleWaits.coerceIn(0, MAX_DOUBLINGS)
    return minOf(MAX_DELAY_SECONDS, BASE_DELAY_SECONDS shl doublings)
}

/**
 * A run that advanced `bytesSent` beyond its starting value resets the count and waits the base
 * delay. A run that did not is the next idle wait: it waits the delay for the idle runs before
 * it, so the first one still runs after 5 seconds, and the next replacement carries a count one
 * higher.
 */
fun planRequeue(previousIdleWaits: Int, bytesSentAtStart: Long, bytesSentNow: Long): RequeuePlan =
    if (bytesSentNow > bytesSentAtStart) {
        RequeuePlan(idleWaits = 0, delaySeconds = requeueDelaySeconds(0))
    } else {
        RequeuePlan(
            idleWaits = previousIdleWaits.coerceAtLeast(0) + 1,
            delaySeconds = requeueDelaySeconds(previousIdleWaits)
        )
    }
