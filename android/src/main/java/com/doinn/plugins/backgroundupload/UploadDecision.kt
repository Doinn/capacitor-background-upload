package com.doinn.plugins.backgroundupload

const val MAX_ATTEMPTS = 5

sealed class UploadDecision {
    data class Uploaded(val httpStatus: Int) : UploadDecision()
    data class Failed(val httpStatus: Int?, val error: String, val attempts: Int) : UploadDecision()
    data class RetryLater(val attempts: Int) : UploadDecision()
}

/**
 * Only a retryable server or transport failure spends an attempt. A lost network waits without
 * counting, and a system stop returns the job to the queue, because neither says the file is bad.
 * Only a cancel requested through the plugin ends the job: it can race a transfer that ends as a
 * retry just before the worker is stopped, so any wait-and-retry answer becomes a cancellation
 * then. A success or a failure with its own cause is reported as it is.
 */
fun decide(attempts: Int, outcome: TransferOutcome, cancelRequested: Boolean): UploadDecision {
    val decision = when (outcome) {
        is TransferOutcome.Success -> UploadDecision.Uploaded(outcome.httpStatus)
        is TransferOutcome.Fail -> UploadDecision.Failed(outcome.httpStatus, outcome.error, attempts)
        is TransferOutcome.Retry -> {
            val next = attempts + 1
            if (next >= MAX_ATTEMPTS) UploadDecision.Failed(outcome.httpStatus, outcome.error, next)
            else UploadDecision.RetryLater(next)
        }
        is TransferOutcome.NetworkLost -> UploadDecision.RetryLater(attempts)
        TransferOutcome.Cancelled -> UploadDecision.RetryLater(attempts)
    }
    return if (cancelRequested && decision is UploadDecision.RetryLater) {
        UploadDecision.Failed(null, "cancelled", attempts)
    } else {
        decision
    }
}
