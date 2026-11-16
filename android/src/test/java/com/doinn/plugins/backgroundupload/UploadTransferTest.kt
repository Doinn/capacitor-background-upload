package com.doinn.plugins.backgroundupload

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploadTransferTest {
    private val total = 614400L
    private val data = ByteArray(total.toInt()) { (it % 251).toByte() }
    private lateinit var server: MockWebServer
    private var network = true
    private var cancelled = false
    private val progress = mutableListOf<Long>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        try { server.shutdown() } catch (_: Exception) {}
    }

    private fun request(resume: Boolean) = TransferRequest(
        url = server.url("/session").toString(),
        headers = mapOf("X-Test" to "1"),
        totalBytes = total,
        resume = resume,
        openStream = { ByteArrayInputStream(data) }
    )

    private fun run(resume: Boolean, onProgress: (Long) -> Unit = {}): TransferOutcome =
        UploadTransfer(client) { network }.run(request(resume), { cancelled }) {
            progress.add(it)
            onProgress(it)
        }

    @Test fun freshUploadSendsWholeBodyWithoutContentRange() {
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(TransferOutcome.Success(200), run(false))
        val recorded = server.takeRequest()
        assertEquals(total, recorded.bodySize)
        assertNull(recorded.getHeader("Content-Range"))
        assertEquals("1", recorded.getHeader("X-Test"))
        assertEquals(1, server.requestCount)
    }

    @Test fun resumeContinuesFromCommittedOffset() {
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-262143"))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(TransferOutcome.Success(200), run(true))
        val probe = server.takeRequest()
        assertEquals("bytes */$total", probe.getHeader("Content-Range"))
        assertEquals(0L, probe.bodySize)
        val upload = server.takeRequest()
        assertEquals("bytes 262144-614399/614400", upload.getHeader("Content-Range"))
        assertEquals(352256L, upload.bodySize)
    }

    @Test fun resumeWithNothingCommittedRestartsFromZero() {
        server.enqueue(MockResponse().setResponseCode(308))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(TransferOutcome.Success(200), run(true))
        server.takeRequest()
        val upload = server.takeRequest()
        assertEquals(total, upload.bodySize)
        assertNull(upload.getHeader("Content-Range"))
    }

    @Test fun probeSaysAlreadyComplete() {
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(TransferOutcome.Success(200), run(true))
        assertEquals(1, server.requestCount)
        assertTrue(progress.isEmpty())
    }

    @Test fun status503IsRetry() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(TransferOutcome.Retry(503, "HTTP 503"), run(false))
    }

    @Test fun status429And408AreRetry() {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(TransferOutcome.Retry(429, "HTTP 429"), run(false))
        server.enqueue(MockResponse().setResponseCode(408))
        assertEquals(TransferOutcome.Retry(408, "HTTP 408"), run(false))
        assertEquals(2, server.requestCount)
    }

    @Test fun status503WithRetryAfterZeroIsNotReplayed() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(TransferOutcome.Retry(503, "HTTP 503"), run(false))
        assertEquals(1, server.requestCount)
    }

    @Test fun probeAnswered503IsRetryWithoutBody() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(TransferOutcome.Retry(503, "HTTP 503"), run(true))
        assertEquals(1, server.requestCount)
    }

    @Test fun probeAnswered404IsFailWithoutBody() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(TransferOutcome.Fail(404, "HTTP 404"), run(true))
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedRangeRestartsFromZero() {
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-99999999999999999999999"))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(TransferOutcome.Success(200), run(true))
        server.takeRequest()
        assertEquals(total, server.takeRequest().bodySize)
    }

    @Test fun committedOffsetEqualToTotalIsSuccessWithoutBody() {
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${total - 1}"))
        assertEquals(TransferOutcome.Success(308), run(true))
        assertEquals(1, server.requestCount)
    }

    @Test fun committedOffsetBeyondTotalFails() {
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-$total"))
        assertEquals(TransferOutcome.Fail(null, "committed offset beyond file size"), run(true))
        assertEquals(1, server.requestCount)
    }

    @Test fun nonIoExceptionFromOpenStreamIsFail() {
        server.enqueue(MockResponse().setResponseCode(200))
        val failing = request(false).copy(openStream = { throw SecurityException("grant revoked") })
        val outcome = UploadTransfer(client) { network }.run(failing, { cancelled }) {}
        assertEquals(TransferOutcome.Fail(null, "grant revoked"), outcome)
    }

    @Test fun fileShorterThanCommittedOffsetIsFail() {
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-262143"))
        val short = request(true).copy(openStream = { ByteArrayInputStream(ByteArray(1000)) })
        val outcome = UploadTransfer(client) { network }.run(short, { cancelled }) {}
        assertEquals(TransferOutcome.Fail(null, "file shorter than committed offset"), outcome)
    }

    @Test fun alreadyCancelledSendsNothing() {
        cancelled = true
        assertEquals(TransferOutcome.Cancelled, run(true))
        assertEquals(0, server.requestCount)
    }

    @Test fun cancelAfterLastByteLetsTheResponseDecide() {
        server.enqueue(MockResponse().setResponseCode(200))
        val outcome = run(false) { if (it == total) cancelled = true }
        assertEquals(TransferOutcome.Success(200), outcome)
    }

    @Test fun callerContentTypeIsSentAndContentRangeIsComputed() {
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-262143"))
        server.enqueue(MockResponse().setResponseCode(200))
        val headers = mapOf("Content-Type" to "video/mp4", "Content-Range" to "bytes 0-1/2")
        val withHeaders = request(true).copy(headers = headers)
        UploadTransfer(client) { network }.run(withHeaders, { cancelled }) {}
        server.takeRequest()
        val upload = server.takeRequest()
        assertEquals("video/mp4", upload.getHeader("Content-Type"))
        assertEquals("bytes 262144-614399/614400", upload.getHeader("Content-Range"))
    }

    @Test fun status404IsFail() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(TransferOutcome.Fail(404, "HTTP 404"), run(false))
    }

    @Test fun connectionDropWithoutNetworkIsNetworkLost() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
        network = false
        assertTrue(run(false) is TransferOutcome.NetworkLost)
    }

    @Test fun connectionDropWithNetworkIsRetry() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
        val outcome = run(false)
        assertTrue(outcome is TransferOutcome.Retry)
        assertNull((outcome as TransferOutcome.Retry).httpStatus)
    }

    @Test fun probeFailureWithoutNetworkIsNetworkLost() {
        server.shutdown()
        network = false
        assertTrue(run(true) is TransferOutcome.NetworkLost)
    }

    @Test fun cancelBetweenChunksIsCancelled() {
        server.enqueue(MockResponse().setResponseCode(200))
        val outcome = run(false) { cancelled = true }
        assertEquals(TransferOutcome.Cancelled, outcome)
        assertEquals(listOf(262144L), progress)
    }

    @Test fun cancelWinsOverNetworkLost() {
        server.shutdown()
        cancelled = true
        network = false
        assertEquals(TransferOutcome.Cancelled, run(true))
    }

    @Test fun progressReportsAbsoluteBytes() {
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-262143"))
        server.enqueue(MockResponse().setResponseCode(200))
        run(true)
        assertEquals(listOf(524288L, 614400L), progress)
    }
}
