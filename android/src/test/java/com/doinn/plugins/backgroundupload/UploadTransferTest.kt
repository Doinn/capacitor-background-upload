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
        // OkHttp silently replays a request once after a 408, so the server must answer twice.
        server.enqueue(MockResponse().setResponseCode(408))
        server.enqueue(MockResponse().setResponseCode(408))
        assertEquals(TransferOutcome.Retry(408, "HTTP 408"), run(false))
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
