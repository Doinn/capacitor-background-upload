package com.doinn.plugins.backgroundupload

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@CapacitorPlugin(name = "BackgroundUpload")
class BackgroundUploadPlugin : Plugin() {

    companion object {
        const val WORK_TAG = "doinn-background-upload"
    }

    private lateinit var store: UploadJobStore
    private var observer: Observer<List<WorkInfo>>? = null
    private var workLiveData: LiveData<List<WorkInfo>>? = null
    private val notifiedTerminal = mutableSetOf<String>()
    private val prepareExecutor = Executors.newSingleThreadExecutor()
    private val zombieSweepStarted = AtomicBoolean(false)

    override fun load() {
        store = UploadJobStore(context)
        // One sweep per process launch, scheduled on the plugin's single-thread
        // executor before load() returns (i.e. before the bridge can dispatch any
        // enqueueUpload/getJobs call), so a fresh enqueue can never be mistaken for a
        // zombie. enqueueUpload/getJobs read/write the store through the same executor
        // below, which guarantees they run after this task without needing a separate
        // latch. Guarded so a re-entrant load() never schedules a second sweep.
        if (zombieSweepStarted.compareAndSet(false, true)) {
            prepareExecutor.execute { sweepZombieJobs() }
        }
        observeWork()
    }

    /**
     * A force-stop (or the process being killed) can leave a WorkManager job dead
     * without [UploadWorker] ever getting to write a terminal state, so the store
     * keeps reporting `queued`/`uploading` forever. Reconcile against WorkManager's
     * own bookkeeping:
     * - No work info at all, or every work info already CANCELLED/FAILED, means the
     *   worker never ran to completion and wrote back - mark it failed so the JS
     *   reconciler can clean it up.
     * - Every work info SUCCEEDED means the upload actually finished but the store
     *   flush was lost (process killed after doWork returned) - recover it as
     *   `uploaded` rather than failing a job that really did complete; the JS
     *   reconciler will confirm it against the backend.
     */
    private fun sweepZombieJobs() {
        val workManager = WorkManager.getInstance(context)
        store.getAll()
            .filter { it.state == "queued" || it.state == "uploading" }
            .forEach { job ->
                val infos = try {
                    workManager.getWorkInfosForUniqueWork(job.jobId).get()
                } catch (e: Exception) {
                    // Can't determine WorkManager's state for this job right now -
                    // skip it for this pass rather than guessing; it will be
                    // re-evaluated on the next process launch.
                    return@forEach
                }
                when {
                    infos.isNotEmpty() && infos.all { it.state == WorkInfo.State.SUCCEEDED } -> {
                        job.state = "uploaded"
                        store.save(job)
                    }
                    infos.isEmpty() || infos.all { it.state.isFinished } -> {
                        job.state = "failed"
                        job.error = "orphaned (app terminated)"
                        store.save(job)
                    }
                }
            }
    }

    override fun handleOnDestroy() {
        observer?.let { workLiveData?.removeObserver(it) }
        observer = null
        workLiveData = null
        prepareExecutor.shutdown()
        super.handleOnDestroy()
    }

    private fun observeWork() {
        val liveData = WorkManager.getInstance(context).getWorkInfosByTagLiveData(WORK_TAG)
        val workObserver = Observer<List<WorkInfo>> { infos ->
            infos.forEach { info -> handleWorkInfo(info) }
        }
        observer = workObserver
        workLiveData = liveData
        liveData.observeForever(workObserver)
    }

    private fun handleWorkInfo(info: WorkInfo) {
        when (info.state) {
            WorkInfo.State.RUNNING -> {
                val jobId = info.progress.getString(UploadWorker.KEY_JOB_ID) ?: return
                val data = JSObject()
                data.put("jobId", jobId)
                data.put("bytesSent", info.progress.getLong(UploadWorker.KEY_PROGRESS_BYTES_SENT, 0))
                data.put("totalBytes", info.progress.getLong(UploadWorker.KEY_PROGRESS_TOTAL, 0))
                notifyListeners("uploadProgress", data)
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> {
                val jobId = info.outputData.getString(UploadWorker.KEY_JOB_ID) ?: return
                if (!notifiedTerminal.add(jobId)) return
                val job = store.get(jobId) ?: return
                val data = JSObject()
                data.put("jobId", jobId)
                if (job.state == "uploaded") {
                    job.httpStatus?.let { data.put("httpStatus", it) }
                    notifyListeners("uploadCompleted", data)
                } else {
                    job.httpStatus?.let { data.put("httpStatus", it) }
                    data.put("error", job.error ?: "unknown")
                    notifyListeners("uploadFailed", data)
                }
            }
            else -> Unit
        }
    }

    @PluginMethod
    fun enqueueUpload(call: PluginCall) {
        val jobId = call.getString("jobId")
        val filePath = call.getString("filePath")
        val url = call.getString("url")
        if (jobId == null || filePath == null || url == null) {
            call.reject("jobId, filePath and url are required")
            return
        }
        val headers = call.getObject("headers") ?: JSObject()
        val notificationTitle = call.getString("notificationTitle") ?: "Doinn"

        // Routed through the same single-thread executor as the startup zombie sweep
        // (see load()/sweepZombieJobs()) so the store write and WorkManager enqueue are
        // ordered after it - a fresh enqueue landing right after load() can no longer be
        // mistaken for a zombie by a still-running sweep.
        prepareExecutor.execute {
            try {
                store.save(UploadJob(jobId, "queued", filePath = filePath))
                notifiedTerminal.remove(jobId)

                val request = OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(
                        workDataOf(
                            UploadWorker.KEY_JOB_ID to jobId,
                            UploadWorker.KEY_FILE_PATH to filePath,
                            UploadWorker.KEY_URL to url,
                            UploadWorker.KEY_HEADERS to headers.toString(),
                            UploadWorker.KEY_NOTIFICATION_TITLE to notificationTitle
                        )
                    )
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .addTag(WORK_TAG)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(jobId, ExistingWorkPolicy.KEEP, request)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "enqueueUpload failed", e)
            }
        }
    }

    @PluginMethod
    fun prepareFile(call: PluginCall) {
        val filePath = call.getString("filePath")
        if (filePath == null) {
            call.reject("filePath is required")
            return
        }
        val imageMaxDimension = call.getInt("imageMaxDimension")
        val imageQuality = call.getDouble("imageQuality")
        val deleteSource = call.getBoolean("deleteSource") ?: false

        prepareExecutor.execute {
            try {
                val prepared = UploadFilePreparer.prepare(context, filePath, imageMaxDimension, imageQuality, deleteSource)
                val result = JSObject()
                result.put("path", prepared.path)
                result.put("size", prepared.size)
                prepared.mimeType?.let { result.put("mimeType", it) }
                call.resolve(result)
            } catch (e: Exception) {
                call.reject(e.message ?: "prepareFile failed", e)
            }
        }
    }

    @PluginMethod
    fun cleanupOrphans(call: PluginCall) {
        // call.getLong requires the JSON value to be a Long specifically; whole numbers
        // small enough to fit an Int (like our 24h default) parse as Integer instead, so
        // getLong would silently ignore a caller-supplied value. Coerce any Number type.
        val maxAgeMs = (call.getData().opt("maxAgeMs") as? Number)?.toLong()
            ?: UploadFilePreparer.DEFAULT_ORPHAN_MAX_AGE_MS

        prepareExecutor.execute {
            try {
                val referencedPaths = store.getAll().mapNotNull { it.filePath }.toSet()
                val deleted = UploadFilePreparer.cleanupOrphans(context, referencedPaths, maxAgeMs)
                val result = JSObject()
                result.put("deleted", deleted)
                call.resolve(result)
            } catch (e: Exception) {
                call.reject(e.message ?: "cleanupOrphans failed", e)
            }
        }
    }

    @PluginMethod
    fun getJobs(call: PluginCall) {
        // Routed through the same single-thread executor as the startup zombie sweep
        // (see load()/sweepZombieJobs()) so the read is ordered after it - no latch
        // needed, the executor's FIFO ordering is the synchronization.
        prepareExecutor.execute {
            val jobs = JSArray()
            store.getAll().forEach { job ->
                val obj = JSObject()
                obj.put("jobId", job.jobId)
                obj.put("state", job.state)
                obj.put("bytesSent", job.bytesSent)
                obj.put("totalBytes", job.totalBytes)
                job.httpStatus?.let { obj.put("httpStatus", it) }
                job.error?.let { obj.put("error", it) }
                jobs.put(obj)
            }
            val result = JSObject()
            result.put("jobs", jobs)
            call.resolve(result)
        }
    }

    @PluginMethod
    fun acknowledge(call: PluginCall) {
        val jobId = call.getString("jobId")
        if (jobId == null) {
            call.reject("jobId is required")
            return
        }
        val job = store.get(jobId)
        job?.filePath?.let { path ->
            if (UploadFilePreparer.isInsideCacheDir(context, path)) {
                runCatching { File(path).delete() }
            }
        }
        store.remove(jobId)
        notifiedTerminal.remove(jobId)
        call.resolve()
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        val jobId = call.getString("jobId")
        if (jobId == null) {
            call.reject("jobId is required")
            return
        }
        WorkManager.getInstance(context).cancelUniqueWork(jobId)
        val job = store.get(jobId)
        if (job != null && job.state != "uploaded") {
            job.state = "failed"
            job.error = "cancelled"
            store.save(job)
        }
        call.resolve()
    }
}
