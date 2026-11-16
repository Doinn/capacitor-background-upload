package com.doinn.plugins.backgroundupload

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

data class TransferRequest(
    val url: String,
    val headers: Map<String, String>,
    val totalBytes: Long,
    val resume: Boolean,
    val openStream: () -> InputStream
)

sealed class TransferOutcome {
    data class Success(val httpStatus: Int) : TransferOutcome()
    data class Retry(val httpStatus: Int?, val error: String) : TransferOutcome()
    data class NetworkLost(val error: String) : TransferOutcome()
    data class Fail(val httpStatus: Int?, val error: String) : TransferOutcome()
    object Cancelled : TransferOutcome()
}

/**
 * Uploads one file to a Google Cloud Storage resumable session URI, independent of any scheduler.
 * The body is streamed from `openStream` in 256 KiB chunks and never held in memory.
 */
class UploadTransfer(
    private val client: OkHttpClient,
    private val hasNetwork: () -> Boolean
) {
    companion object {
        const val CHUNK_BYTES = 262144
    }

    /**
     * An IOException is checked against `isCancelled` first because cancelling interrupts the
     * stream, which surfaces as an IOException too. Only then does connectivity decide between
     * NetworkLost, which the caller must not count as an attempt, and Retry, which it must.
     */
    fun run(
        request: TransferRequest,
        isCancelled: () -> Boolean,
        onProgress: (bytesSent: Long) -> Unit
    ): TransferOutcome {
        return try {
            val probe = if (request.resume) probeCommittedOffset(request) else Probe(0L, null)
            if (probe.completeStatus != null) {
                TransferOutcome.Success(probe.completeStatus)
            } else {
                put(request, probe.offset, isCancelled, onProgress)
            }
        } catch (e: IOException) {
            when {
                isCancelled() -> TransferOutcome.Cancelled
                !hasNetwork() -> TransferOutcome.NetworkLost(e.message ?: e.javaClass.simpleName)
                else -> TransferOutcome.Retry(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private class Probe(val offset: Long, val completeStatus: Int?)

    /**
     * Asks the session how many bytes it already holds: a PUT with `Content-Range: bytes * /total`
     * and an empty body. 200 or 201 means the object is complete, 308 with a `Range` header
     * names the committed prefix, and anything else restarts from zero.
     */
    private fun probeCommittedOffset(request: TransferRequest): Probe {
        val probe = Request.Builder()
            .url(request.url)
            .put(ByteArray(0).toRequestBody(null))
            .header("Content-Range", "bytes */${request.totalBytes}")
            .build()
        return client.newCall(probe).execute().use { response ->
            when (response.code) {
                200, 201 -> Probe(request.totalBytes, response.code)
                308 -> Probe(
                    Regex("bytes=0-(\\d+)").find(response.header("Range") ?: "")
                        ?.groupValues?.get(1)?.toLong()?.plus(1) ?: 0L,
                    null
                )
                else -> Probe(0L, null)
            }
        }
    }

    private fun put(
        request: TransferRequest,
        offset: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit
    ): TransferOutcome {
        val builder = Request.Builder().url(request.url)
            .put(streamingBody(request, offset, isCancelled, onProgress))
        if (offset > 0) {
            builder.header(
                "Content-Range",
                "bytes $offset-${request.totalBytes - 1}/${request.totalBytes}"
            )
        }
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        return client.newCall(builder.build()).execute().use { response ->
            val status = response.code
            when (RetryClassifier.classify(status)) {
                HttpVerdict.SUCCESS -> TransferOutcome.Success(status)
                HttpVerdict.RETRY -> TransferOutcome.Retry(status, "HTTP $status")
                HttpVerdict.FAIL -> TransferOutcome.Fail(status, "HTTP $status")
            }
        }
    }

    private fun streamingBody(
        request: TransferRequest,
        offset: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit
    ): RequestBody = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = request.totalBytes - offset

        override fun writeTo(sink: BufferedSink) {
            request.openStream().use { stream ->
                var toSkip = offset
                while (toSkip > 0) {
                    val skipped = stream.skip(toSkip)
                    if (skipped <= 0) throw InterruptedIOException("cannot seek to offset $offset")
                    toSkip -= skipped
                }
                val chunk = ByteArray(CHUNK_BYTES)
                var sent = offset
                while (true) {
                    val filled = fill(stream, chunk)
                    if (filled == 0) break
                    sink.write(chunk, 0, filled)
                    sink.flush()
                    sent += filled
                    onProgress(sent)
                    if (isCancelled()) throw InterruptedIOException("cancelled")
                }
            }
        }
    }

    /** Streams may return short reads; progress must still advance in whole chunks. */
    private fun fill(stream: InputStream, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val read = stream.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }
}
