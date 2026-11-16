package com.doinn.plugins.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadDecisionTest {
    @Test fun successIsUploaded() {
        assertEquals(UploadDecision.Uploaded(200), decide(2, TransferOutcome.Success(200), false))
    }

    @Test fun failKeepsAttempts() {
        assertEquals(
            UploadDecision.Failed(404, "HTTP 404", 1),
            decide(1, TransferOutcome.Fail(404, "HTTP 404"), false)
        )
    }

    @Test fun retryCountsAnAttempt() {
        assertEquals(
            UploadDecision.RetryLater(1),
            decide(0, TransferOutcome.Retry(503, "HTTP 503"), false)
        )
    }

    @Test fun fifthRetryableFailureFails() {
        assertEquals(
            UploadDecision.Failed(503, "HTTP 503", 5),
            decide(4, TransferOutcome.Retry(503, "HTTP 503"), false)
        )
    }

    @Test fun networkLostNeverCounts() {
        assertEquals(UploadDecision.RetryLater(4), decide(4, TransferOutcome.NetworkLost("x"), false))
    }

    @Test fun stopBySystemGoesBackToQueue() {
        assertEquals(UploadDecision.RetryLater(3), decide(3, TransferOutcome.Cancelled, false))
    }

    @Test fun stopByCancelRequestFails() {
        assertEquals(
            UploadDecision.Failed(null, "cancelled", 3),
            decide(3, TransferOutcome.Cancelled, true)
        )
    }
}
