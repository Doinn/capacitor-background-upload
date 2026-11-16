package com.doinn.plugins.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadStartTest {
    private val mine = "process-a"

    @Test fun recordFromThisProcessRuns() {
        val job = UploadJob("a", "queued", processToken = mine)
        assertEquals(StartDecision.RUN, decideStart(job, mine))
    }

    @Test fun interruptedRecordFromThisProcessRunsAgain() {
        val job = UploadJob("a", "uploading", processToken = mine)
        assertEquals(StartDecision.RUN, decideStart(job, mine))
    }

    @Test fun recordFromAnotherProcessIsOrphaned() {
        val job = UploadJob("a", "queued", processToken = "process-b")
        assertEquals(StartDecision.ORPHANED, decideStart(job, mine))
    }

    @Test fun recordWithoutTokenIsOrphaned() {
        val job = UploadJob("a", "queued", processToken = null)
        assertEquals(StartDecision.ORPHANED, decideStart(job, mine))
    }

    @Test fun uploadedRecordIsNeverRestartedWhateverItsOwner() {
        assertEquals(StartDecision.ALREADY_UPLOADED, decideStart(UploadJob("a", "uploaded", processToken = mine), mine))
        assertEquals(StartDecision.ALREADY_UPLOADED, decideStart(UploadJob("a", "uploaded", processToken = "x"), mine))
        assertEquals(StartDecision.ALREADY_UPLOADED, decideStart(UploadJob("a", "uploaded"), mine))
    }

    @Test fun failedRecordIsNeverRestartedWhateverItsOwner() {
        assertEquals(StartDecision.ALREADY_FAILED, decideStart(UploadJob("a", "failed", processToken = mine), mine))
        assertEquals(StartDecision.ALREADY_FAILED, decideStart(UploadJob("a", "failed", processToken = "x"), mine))
        assertEquals(StartDecision.ALREADY_FAILED, decideStart(UploadJob("a", "failed"), mine))
    }

    @Test fun cancelRequestedRecordStopsBeforeOwnershipIsChecked() {
        val job = UploadJob("a", "queued", processToken = "process-b", cancelRequested = true)
        assertEquals(StartDecision.CANCEL_REQUESTED, decideStart(job, mine))
    }

    @Test fun cancelRequestedAfterUploadStillReportsTheUpload() {
        val job = UploadJob("a", "uploaded", processToken = mine, cancelRequested = true)
        assertEquals(StartDecision.ALREADY_UPLOADED, decideStart(job, mine))
    }

    @Test fun cancelledRecordIsReportedAsCancelRequestedNotAsFailed() {
        val job = UploadJob("a", "failed", error = "cancelled", cancelRequested = true)
        assertEquals(StartDecision.CANCEL_REQUESTED, decideStart(job, mine))
    }
}
