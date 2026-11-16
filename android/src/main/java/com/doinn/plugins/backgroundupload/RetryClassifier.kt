package com.doinn.plugins.backgroundupload

enum class HttpVerdict { SUCCESS, RETRY, FAIL }

/** Maps an HTTP status to what the upload loop should do next. */
object RetryClassifier {
    fun classify(status: Int): HttpVerdict = when (status) {
        in 200..299 -> HttpVerdict.SUCCESS
        308, 408, 429, in 500..599 -> HttpVerdict.RETRY
        else -> HttpVerdict.FAIL
    }
}
