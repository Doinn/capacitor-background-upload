package com.doinn.plugins.backgroundupload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject

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
        const val MAX_ATTEMPTS = 5
    }

    private val store = UploadJobStore(appContext)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        if (isStopped) return markCancelled(jobId)
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return failJob(jobId, "missing filePath")
        val url = inputData.getString(KEY_URL) ?: return failJob(jobId, "missing url")
        val headersJson = inputData.getString(KEY_HEADERS) ?: "{}"
        val title = inputData.getString(KEY_NOTIFICATION_TITLE) ?: "Doinn"

        val notificationId = NOTIFICATION_ID_BASE + (jobId.hashCode() and 0x7fffffff) % 10000

        try {
            setForeground(createForegroundInfo(title, notificationId))
        } catch (e: Exception) {
            // FGS pode ser recusado (quota Android 14+); upload segue como worker normal
        }

        val job = store.get(jobId) ?: UploadJob(jobId, "queued")
        if (isStopped) return markCancelled(jobId)
        job.state = "uploading"
        store.save(job)

        return try {
            val (length, contentType) = resolveFileMeta(filePath)
            if (isStopped) return markCancelled(jobId)
            job.totalBytes = length
            store.save(job)

            val client = OkHttpClient.Builder().build()

            val startOffset = if (runAttemptCount > 0) {
                probeCommittedOffset(client, url, length)
            } else 0L

            if (startOffset >= length) {
                job.state = "uploaded"
                job.bytesSent = length
                store.save(job)
                return Result.success(workDataOf(KEY_JOB_ID to jobId))
            }

            val body = streamingBody(filePath, startOffset, length, contentType) { sent ->
                job.bytesSent = sent
                store.save(job)
                setProgressAsync(
                    workDataOf(
                        KEY_JOB_ID to jobId,
                        KEY_PROGRESS_BYTES_SENT to sent,
                        KEY_PROGRESS_TOTAL to length
                    )
                )
            }

            val requestBuilder = Request.Builder().url(url).put(body)
            if (startOffset > 0) {
                requestBuilder.header("Content-Range", "bytes $startOffset-${length - 1}/$length")
            }
            val headers = JSONObject(headersJson)
            headers.keys().forEach { key ->
                requestBuilder.header(key, headers.getString(key))
            }

            val response = client.newCall(requestBuilder.build()).execute()
            response.use {
                if (it.isSuccessful) {
                    if (isStopped) {
                        return markCancelled(jobId)
                    }
                    job.state = "uploaded"
                    job.httpStatus = it.code
                    job.bytesSent = length
                    store.save(job)
                    Result.success(workDataOf(KEY_JOB_ID to jobId))
                } else {
                    if (isStopped) {
                        return markCancelled(jobId)
                    }
                    if ((it.code in 500..599 || it.code == 308) && runAttemptCount < MAX_ATTEMPTS) {
                        Result.retry()
                    } else {
                        job.state = "failed"
                        job.httpStatus = it.code
                        job.error = "HTTP ${it.code}"
                        store.save(job)
                        Result.failure(workDataOf(KEY_JOB_ID to jobId))
                    }
                }
            }
        } catch (e: Exception) {
            if (isStopped) {
                markCancelled(jobId)
            } else if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                failJob(jobId, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun failJob(jobId: String, message: String): Result {
        val job = store.get(jobId) ?: UploadJob(jobId, "failed")
        job.state = "failed"
        job.error = message
        store.save(job)
        return Result.failure(workDataOf(KEY_JOB_ID to jobId))
    }

    private fun markCancelled(jobId: String): Result {
        val job = store.get(jobId) ?: UploadJob(jobId, "failed")
        job.state = "failed"
        job.error = "cancelled"
        store.save(job)
        return Result.failure(workDataOf(KEY_JOB_ID to jobId))
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

    /**
     * Asks the GCS resumable session how many bytes it already has
     * (PUT with a wildcard Content-Range header, e.g. "bytes (star)/total", and empty body).
     * 200/201 -> whole object committed; 308 + Range header -> committed prefix;
     * anything else (or any error) -> 0 (restart from scratch, fase 1 behavior).
     */
    private fun probeCommittedOffset(client: OkHttpClient, url: String, totalBytes: Long): Long {
        return try {
            val request = Request.Builder()
                .url(url)
                .put(ByteArray(0).toRequestBody(null))
                .header("Content-Range", "bytes */$totalBytes")
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 200 || response.code == 201 -> totalBytes
                    response.code == 308 -> {
                        val range = response.header("Range")
                        val match = Regex("bytes=0-(\\d+)").find(range ?: "")
                        match?.groupValues?.get(1)?.toLong()?.plus(1) ?: 0L
                    }
                    else -> 0L
                }
            }
        } catch (e: Exception) {
            0L
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

    private fun streamingBody(
        filePath: String,
        offset: Long,
        totalLength: Long,
        contentType: String?,
        onProgress: (Long) -> Unit
    ): RequestBody {
        return object : RequestBody() {
            override fun contentType(): MediaType? =
                (contentType ?: "application/octet-stream").toMediaTypeOrNull()

            override fun contentLength(): Long = totalLength - offset

            override fun writeTo(sink: BufferedSink) {
                openStream(filePath).use { stream ->
                    var toSkip = offset
                    while (toSkip > 0) {
                        val skipped = stream.skip(toSkip)
                        if (skipped <= 0) throw InterruptedIOException("cannot seek to offset $offset")
                        toSkip -= skipped
                    }
                    stream.source().use { source ->
                        var total = offset
                        var read: Long
                        while (source.read(sink.buffer, 262144).also { read = it } != -1L) {
                            if (isStopped) {
                                throw InterruptedIOException("cancelled")
                            }
                            total += read
                            sink.flush()
                            onProgress(total)
                        }
                    }
                }
            }
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
