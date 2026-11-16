package com.doinn.plugins.backgroundupload

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UploadJobTest {
    @Test
    fun roundTripKeepsNewFields() {
        val job = UploadJob("a", "queued", attempts = 3, createdAt = 1700000000000, cancelRequested = true)
        val parsed = UploadJob.fromJson(job.toJson())
        assertEquals(3, parsed.attempts)
        assertEquals(1700000000000, parsed.createdAt)
        assertTrue(parsed.cancelRequested)
    }

    @Test
    fun recordFromV100ParsesWithDefaults() {
        val parsed = UploadJob.fromJson(
            JSONObject("""{"jobId":"a","state":"uploading","bytesSent":10,"totalBytes":20}""")
        )
        assertEquals(0, parsed.attempts)
        assertEquals(0L, parsed.createdAt)
        assertFalse(parsed.cancelRequested)
    }

    @Test
    fun missingOrNullErrorReadsAsNull() {
        val missing = UploadJob.fromJson(JSONObject("""{"jobId":"a","state":"queued"}"""))
        val explicitNull = UploadJob.fromJson(JSONObject("""{"jobId":"a","state":"queued","error":null}"""))
        val present = UploadJob.fromJson(JSONObject("""{"jobId":"a","state":"failed","error":"HTTP 404"}"""))
        assertNull(missing.error)
        assertNull(explicitNull.error)
        assertEquals("HTTP 404", present.error)
    }

    @Test
    fun updateOnMissingRecordWritesNothing() {
        val store = UploadJobStore(FakePrefs())
        assertNull(store.update("gone") { it.bytesSent = 5 })
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun updateOnExistingRecordPersists() {
        val store = UploadJobStore(FakePrefs())
        store.save(UploadJob("a", "queued"))
        store.update("a") { it.bytesSent = 5 }
        assertEquals(5L, store.get("a")!!.bytesSent)
    }

    @Test
    fun clearReturnsAndRemovesEverything() {
        val store = UploadJobStore(FakePrefs())
        store.save(UploadJob("a", "queued"))
        store.save(UploadJob("b", "uploading"))
        assertEquals(setOf("a", "b"), store.clear().map { it.jobId }.toSet())
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun removeDuringUpdateIsNotUndoneByTheUpdate() {
        val prefs = FakePrefs()
        val updater = UploadJobStore(prefs)
        val remover = UploadJobStore(prefs)
        updater.save(UploadJob("a", "uploading"))
        val inTransform = CountDownLatch(1)
        val removal = thread(start = false) { remover.remove("a") }

        val updating = thread {
            updater.update("a") {
                inTransform.countDown()
                removal.start()
                Thread.sleep(200)
                it.bytesSent = 5
            }
        }
        assertTrue(inTransform.await(5, TimeUnit.SECONDS))
        updating.join(5000)
        removal.join(5000)

        assertNull(updater.get("a"))
    }

    @Test
    fun concurrentUpdatesDoNotLoseFields() {
        val prefs = FakePrefs()
        val a = UploadJobStore(prefs)
        val b = UploadJobStore(prefs)
        a.save(UploadJob("a", "uploading"))
        val inTransform = CountDownLatch(1)
        val cancelling = thread(start = false) { b.update("a") { it.cancelRequested = true } }

        val progressing = thread {
            a.update("a") {
                inTransform.countDown()
                cancelling.start()
                Thread.sleep(200)
                it.bytesSent = 5
            }
        }
        assertTrue(inTransform.await(5, TimeUnit.SECONDS))
        progressing.join(5000)
        cancelling.join(5000)

        val stored = a.get("a")!!
        assertEquals(5L, stored.bytesSent)
        assertTrue(stored.cancelRequested)
    }
}

private class FakePrefs : SharedPreferences {
    private val data = java.util.concurrent.ConcurrentHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableListOf<() -> Unit>()

        override fun putString(key: String, value: String?) = apply { pending += { data[key] = value } }
        override fun remove(key: String) = apply { pending += { data.remove(key) } }
        override fun clear() = apply { pending += { data.clear() } }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { pending.forEach { it() }; pending.clear() }

        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
    }
}
