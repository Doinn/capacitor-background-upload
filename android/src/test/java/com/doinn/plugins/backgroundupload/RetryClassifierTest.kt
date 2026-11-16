package com.doinn.plugins.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryClassifierTest {
    @Test fun successFor2xx() {
        listOf(200, 201, 204).forEach { assertEquals(HttpVerdict.SUCCESS, RetryClassifier.classify(it)) }
    }

    @Test fun retryForTransientStatuses() {
        listOf(308, 408, 429, 500, 502, 503, 599).forEach { assertEquals(HttpVerdict.RETRY, RetryClassifier.classify(it)) }
    }

    @Test fun failForOther4xxAndUnknown() {
        listOf(400, 401, 403, 404, 410, 412, 301, 600).forEach { assertEquals(HttpVerdict.FAIL, RetryClassifier.classify(it)) }
    }
}
