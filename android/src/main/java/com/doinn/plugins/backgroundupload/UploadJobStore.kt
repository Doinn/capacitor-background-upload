package com.doinn.plugins.backgroundupload

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class UploadJob(
    val jobId: String,
    var state: String, // queued | uploading | uploaded | failed
    var bytesSent: Long = 0,
    var totalBytes: Long = 0,
    var httpStatus: Int? = null,
    var error: String? = null,
    var filePath: String? = null,
    var attempts: Int = 0,
    var createdAt: Long = 0,
    var cancelRequested: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("jobId", jobId)
        put("state", state)
        put("bytesSent", bytesSent)
        put("totalBytes", totalBytes)
        httpStatus?.let { put("httpStatus", it) }
        error?.let { put("error", it) }
        filePath?.let { put("filePath", it) }
        put("attempts", attempts)
        put("createdAt", createdAt)
        put("cancelRequested", cancelRequested)
    }

    companion object {
        fun fromJson(json: JSONObject): UploadJob = UploadJob(
            jobId = json.getString("jobId"),
            state = json.getString("state"),
            bytesSent = json.optLong("bytesSent", 0),
            totalBytes = json.optLong("totalBytes", 0),
            httpStatus = if (json.has("httpStatus")) json.getInt("httpStatus") else null,
            error = json.optString("error", null),
            filePath = if (json.has("filePath")) json.getString("filePath") else null,
            attempts = json.optInt("attempts", 0),
            createdAt = json.optLong("createdAt", 0),
            cancelRequested = json.optBoolean("cancelRequested", false)
        )
    }
}

class UploadJobStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences("DoinnBackgroundUpload", Context.MODE_PRIVATE)
    )

    fun save(job: UploadJob) {
        prefs.edit().putString(job.jobId, job.toJson().toString()).apply()
    }

    fun get(jobId: String): UploadJob? =
        prefs.getString(jobId, null)?.let { UploadJob.fromJson(JSONObject(it)) }

    fun getAll(): List<UploadJob> =
        prefs.all.values.filterIsInstance<String>().map { UploadJob.fromJson(JSONObject(it)) }

    fun remove(jobId: String) {
        prefs.edit().remove(jobId).apply()
    }

    /**
     * Applies [transform] to the stored record and saves it. Writes nothing and returns null when
     * the record is absent, so a worker still running after [clear] cannot bring its record back.
     */
    fun update(jobId: String, transform: (UploadJob) -> Unit): UploadJob? {
        val job = get(jobId) ?: return null
        transform(job)
        save(job)
        return job
    }

    fun clear(): List<UploadJob> {
        val removed = getAll()
        prefs.edit().clear().apply()
        return removed
    }
}
