package com.doinn.plugins.backgroundupload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Hosts one [UploadTransfer] inside WorkManager and records what [decide] says about its outcome.
 * It owns no retry policy: the attempt count lives in the job record and the rules in [decide].
 */
class UploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_FILE_PATH = "filePath"
        const val KEY_URL = "url"
        const val KEY_HEADERS = "headers"
        const val KEY_NOTIFICATION_TITLE = "notificationTitle"
        const val KEY_PROGRESS_BYTES_SENT = "bytesSent"
        const val KEY_PROGRESS_TOTAL = "totalBytes"
        const val CHANNEL_ID = "doinn_background_upload"
        const val NOTIFICATION_ID_BASE = 42918
        private const val TAG = "UploadWorker"
    }

    private val store = UploadJobStore(appContext)
    private val recordRemoved = AtomicBoolean(false)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_NOTIFICATION_TITLE) ?: "Doinn"

        val job = store.update(jobId) { if (!it.cancelRequested) it.state = "uploading" }
            ?: return Result.failure()
        if (job.cancelRequested) return Result.failure()

        val notificationId = NOTIFICATION_ID_BASE + (jobId.hashCode() and 0x7fffffff) % 10000
        try {
            setForeground(createForegroundInfo(title, notificationId))
        } catch (e: Exception) {
            Log.w(TAG, "Running without a foreground service: ${e.javaClass.simpleName}")
        }

        // The final write must survive a stop: a cancelled coroutine would discard the result of
        // withContext and leave the record at "uploading". The transfer polls isStopped itself.
        return withContext(Dispatchers.IO + NonCancellable) { transfer(job) }
    }

    private fun transfer(job: UploadJob): Result {
        val jobId = job.jobId
        val request = try {
            buildRequest(job)
        } catch (e: Exception) {
            val error = e.message ?: e.javaClass.simpleName
            return settle(jobId) { UploadDecision.Failed(null, error, it.attempts) }
        }
        if (!record(jobId) { it.totalBytes = request.totalBytes }) return Result.failure()

        val outcome = UploadTransfer(OkHttpClient.Builder().build(), ::hasNetwork).run(
            request,
            isCancelled = { isStopped || recordRemoved.get() },
            onProgress = { sent -> reportProgress(jobId, sent, request.totalBytes) }
        )
        return settle(jobId) { decide(it.attempts, outcome, it.cancelRequested) }
    }

    private fun buildRequest(job: UploadJob): TransferRequest {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: throw IllegalStateException("missing filePath")
        val url = inputData.getString(KEY_URL) ?: throw IllegalStateException("missing url")
        val headersJson = JSONObject(inputData.getString(KEY_HEADERS) ?: "{}")
        val (length, contentType) = resolveFileMeta(filePath)
        val headers = mutableMapOf<String, String>()
        headersJson.keys().forEach { key -> headers[key] = headersJson.getString(key) }
        if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            headers["Content-Type"] = contentType ?: "application/octet-stream"
        }
        return TransferRequest(
            url = url,
            headers = headers,
            totalBytes = length,
            resume = job.bytesSent > 0 || runAttemptCount > 0,
            openStream = { openStream(filePath) }
        )
    }

    /**
     * The transfer calls this from inside the request body and turns any exception into a
     * terminal failure, so a store or WorkManager error must not escape and end an upload that
     * could still succeed.
     */
    private fun reportProgress(jobId: String, sent: Long, total: Long) {
        try {
            if (!record(jobId) { it.bytesSent = sent }) return
            setProgressAsync(
                workDataOf(
                    KEY_JOB_ID to jobId,
                    KEY_PROGRESS_BYTES_SENT to sent,
                    KEY_PROGRESS_TOTAL to total
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Progress report failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Applies a store update and remembers when the record is gone, so the transfer stops at its
     * next chunk and nothing writes the record back. Returns false once the record is gone.
     */
    private fun record(jobId: String, transform: (UploadJob) -> Unit): Boolean {
        if (recordRemoved.get()) return false
        if (store.update(jobId, transform) == null) {
            recordRemoved.set(true)
            return false
        }
        return true
    }

    /**
     * The decision is taken inside the store update, from the freshest record, so a cancel
     * request or an attempt count written meanwhile is honoured.
     */
    private fun settle(jobId: String, choose: (UploadJob) -> UploadDecision): Result {
        if (recordRemoved.get()) return Result.failure()
        val settled = store.update(jobId) { job ->
            when (val decision = choose(job)) {
                is UploadDecision.Uploaded -> {
                    job.state = "uploaded"
                    job.httpStatus = decision.httpStatus
                    job.bytesSent = job.totalBytes
                }
                is UploadDecision.Failed -> {
                    job.state = "failed"
                    job.httpStatus = decision.httpStatus
                    job.error = decision.error
                    job.attempts = decision.attempts
                }
                is UploadDecision.RetryLater -> {
                    job.state = "queued"
                    job.attempts = decision.attempts
                }
            }
        }
        if (settled == null) {
            recordRemoved.set(true)
            return Result.failure()
        }
        val output = workDataOf(KEY_JOB_ID to jobId)
        return when (settled.state) {
            "uploaded" -> Result.success(output)
            "failed" -> Result.failure(output)
            else -> Result.retry()
        }
    }

    /** A failed read of connectivity counts as online, so the failure becomes a counted retry. */
    private fun hasNetwork(): Boolean = try {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        true
    }

    private fun resolveFileMeta(filePath: String): Pair<Long, String?> {
        return if (filePath.startsWith("content://")) {
            val uri = Uri.parse(filePath)
            val resolver = applicationContext.contentResolver
            val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (length < 0) throw IllegalStateException("Cannot determine size of $filePath")
            Pair(length, resolver.getType(uri))
        } else {
            val file = File(Uri.parse(filePath).path ?: filePath)
            if (!file.exists()) throw IllegalStateException("File not found: $filePath")
            Pair(file.length(), null)
        }
    }

    private fun openStream(filePath: String): InputStream {
        return if (filePath.startsWith("content://")) {
            applicationContext.contentResolver.openInputStream(Uri.parse(filePath))
                ?: throw IllegalStateException("Cannot open $filePath")
        } else {
            File(Uri.parse(filePath).path ?: filePath).inputStream()
        }
    }

    private fun createForegroundInfo(title: String, notificationId: Int): ForegroundInfo {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
