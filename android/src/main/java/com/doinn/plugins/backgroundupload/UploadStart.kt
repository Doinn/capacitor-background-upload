package com.doinn.plugins.backgroundupload

import java.util.UUID

/**
 * Identifies the process that owns a job. A job record carries the token of the process that
 * enqueued it, so a run in a later process can tell that the JavaScript which asked for the
 * upload is gone.
 */
object ProcessIdentity {
    val token: String = UUID.randomUUID().toString()
}

enum class StartDecision { RUN, ORPHANED, ALREADY_UPLOADED, ALREADY_FAILED, CANCEL_REQUESTED }

/**
 * What a run does with the record it finds, checked in this order so that the first match wins:
 * an `uploaded` record is reported as it is, even after a cancel request; a record with a cancel
 * request ends quietly; any other `failed` record is reported as it is; and only then is the
 * owner compared. A terminal record from an earlier process must stay as it is, so ownership
 * comes last, and a record with no token at all (written by an older version) is orphaned.
 */
fun decideStart(job: UploadJob, processToken: String): StartDecision = when {
    job.state == "uploaded" -> StartDecision.ALREADY_UPLOADED
    job.cancelRequested -> StartDecision.CANCEL_REQUESTED
    job.state == "failed" -> StartDecision.ALREADY_FAILED
    job.processToken != processToken -> StartDecision.ORPHANED
    else -> StartDecision.RUN
}
