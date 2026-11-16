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
 * Only a cancel requested through the plugin ends the job.
 */
fun decide(attempts: Int, outcome: TransferOutcome, cancelRequested: Boolean): UploadDecision =
    when (outcome) {
        is TransferOutcome.Success -> UploadDecision.Uploaded(outcome.httpStatus)
        is TransferOutcome.Fail -> UploadDecision.Failed(outcome.httpStatus, outcome.error, attempts)
        is TransferOutcome.Retry -> {
            val next = attempts + 1
            if (next >= MAX_ATTEMPTS) UploadDecision.Failed(outcome.httpStatus, outcome.error, next)
            else UploadDecision.RetryLater(next)
        }
        is TransferOutcome.NetworkLost -> UploadDecision.RetryLater(attempts)
        TransferOutcome.Cancelled ->
            if (cancelRequested) UploadDecision.Failed(null, "cancelled", attempts)
            else UploadDecision.RetryLater(attempts)
    }
