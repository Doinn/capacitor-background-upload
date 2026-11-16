package com.doinn.plugins.backgroundupload

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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
     * Any other exception, such as a revoked content grant, cannot be cured by retrying.
     */
    fun run(
        request: TransferRequest,
        isCancelled: () -> Boolean,
        onProgress: (bytesSent: Long) -> Unit
    ): TransferOutcome {
        if (isCancelled()) return TransferOutcome.Cancelled
        return try {
            val probe = if (request.resume) probeCommittedOffset(request) else Probe(0L, null)
            probe.outcome ?: put(request, probe.offset, isCancelled, onProgress)
        } catch (e: FileTooShortException) {
            TransferOutcome.Fail(null, "file shorter than committed offset")
        } catch (e: IOException) {
            when {
                isCancelled() -> TransferOutcome.Cancelled
                !hasNetwork() -> TransferOutcome.NetworkLost(e.message ?: e.javaClass.simpleName)
                else -> TransferOutcome.Retry(null, e.message ?: e.javaClass.simpleName)
            }
        } catch (e: RuntimeException) {
            if (isCancelled()) TransferOutcome.Cancelled
            else TransferOutcome.Fail(null, e.message ?: e.javaClass.simpleName)
        }
    }

    private class Probe(val offset: Long, val outcome: TransferOutcome?)

    private class FileTooShortException : IOException()

    /**
     * Asks the session how many bytes it already holds: a PUT with `Content-Range: bytes * /total`
     * and an empty body. A 308 names the committed prefix in `Range`, or nothing when no byte
     * is committed. Every other status is classified like a data response, so an unavailable
     * or expired session is never answered by re-sending the file from byte 0.
     */
    private fun probeCommittedOffset(request: TransferRequest): Probe {
        val probe = Request.Builder()
            .url(request.url)
            .put(ByteArray(0).toRequestBody(null))
            .header("Content-Range", "bytes */${request.totalBytes}")
            .build()
        return client.newCall(probe).execute().use { response ->
            val status = response.code
            if (status == 308) {
                val committed = Regex("bytes=0-(\\d+)").find(response.header("Range") ?: "")
                    ?.groupValues?.get(1)?.toLongOrNull()?.plus(1) ?: 0L
                when {
                    committed > request.totalBytes ->
                        Probe(0L, TransferOutcome.Fail(null, "committed offset beyond file size"))
                    committed == request.totalBytes -> Probe(committed, TransferOutcome.Success(status))
                    else -> Probe(committed, null)
                }
            } else {
                Probe(0L, classified(status))
            }
        }
    }

    private fun classified(status: Int): TransferOutcome = when (RetryClassifier.classify(status)) {
        HttpVerdict.SUCCESS -> TransferOutcome.Success(status)
        HttpVerdict.RETRY -> TransferOutcome.Retry(status, "HTTP $status")
        HttpVerdict.FAIL -> TransferOutcome.Fail(status, "HTTP $status")
    }

    private fun put(
        request: TransferRequest,
        offset: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit
    ): TransferOutcome {
        val builder = Request.Builder().url(request.url)
            .put(streamingBody(request, offset, isCancelled, onProgress))
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        if (offset > 0) {
            builder.header(
                "Content-Range",
                "bytes $offset-${request.totalBytes - 1}/${request.totalBytes}"
            )
        }
        return client.newCall(builder.build()).execute().use { classified(it.code) }
    }

    /**
     * The body is one-shot: OkHttp would otherwise replay it after a 408, a 503 with
     * `Retry-After: 0` or a connection failure, re-sending from a stale offset without the caller
     * counting an attempt. A replay that slips through is refused here.
     */
    private fun streamingBody(
        request: TransferRequest,
        offset: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit
    ): RequestBody = object : RequestBody() {
        private var written = false

        override fun contentType() = request.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.toMediaTypeOrNull()

        override fun contentLength(): Long = request.totalBytes - offset

        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            if (written) throw IOException("request body already consumed")
            written = true
            request.openStream().use { stream ->
                skipTo(stream, offset)
                val chunk = ByteArray(CHUNK_BYTES)
                var sent = offset
                while (true) {
                    val filled = fill(stream, chunk)
                    if (filled == 0) break
                    sink.write(chunk, 0, filled)
                    sink.flush()
                    sent += filled
                    onProgress(sent)
                    if (sent < request.totalBytes && isCancelled()) {
                        throw InterruptedIOException("cancelled")
                    }
                }
            }
        }
    }

    /** `skip` may return 0 before the end of the file, so a single byte is read to tell them apart. */
    private fun skipTo(stream: InputStream, offset: Long) {
        var toSkip = offset
        while (toSkip > 0) {
            val skipped = stream.skip(toSkip)
            if (skipped > 0) {
                toSkip -= skipped
            } else {
                if (stream.read() < 0) throw FileTooShortException()
                toSkip--
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
