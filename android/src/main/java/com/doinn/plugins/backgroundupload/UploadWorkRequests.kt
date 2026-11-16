package com.doinn.plugins.backgroundupload

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import java.util.concurrent.TimeUnit

/**
 * The one place that shapes an upload work request, so a request enqueued by the plugin and the
 * replacement the worker enqueues for itself cannot drift apart.
 */
fun buildUploadWorkRequest(jobId: String, input: Data, initialDelaySeconds: Long = 0): OneTimeWorkRequest {
    val builder = OneTimeWorkRequestBuilder<UploadWorker>()
        .setInputData(Data.Builder().putAll(input).putString(UploadWorker.KEY_JOB_ID, jobId).build())
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
        .addTag(BackgroundUploadPlugin.WORK_TAG)
    if (initialDelaySeconds > 0) builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
    return builder.build()
}
