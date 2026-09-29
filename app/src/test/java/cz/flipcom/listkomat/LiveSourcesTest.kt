package cz.flipcom.listkomat

import cz.flipcom.listkomat.data.BrnoLiveStreamSource
import cz.flipcom.listkomat.data.BrnoStream
import cz.flipcom.listkomat.data.FailureStreak
import cz.flipcom.listkomat.data.InterestTracker
import cz.flipcom.listkomat.data.PragueLiveSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSourcesTest {

    // --- Wedge recovery (#8 defect 1) ---

    @Test
    fun `failure streak trips from the second failure in a row and resets on success`() {
        var trips = 0
        val streak = FailureStreak(2) { trips++ }
        streak.failure()
        assertEquals(0, trips)
        streak.failure()
        assertEquals(1, trips)
        streak.failure()
        assertEquals(2, trips)
        streak.success()
        streak.failure()
        assertEquals(2, trips)
    }

    @Test
    fun `prague evicts the pool on the second consecutive failure, keeps the first-failure error`() = runTest {
        var evictions = 0
        var nextBody: String? = null
        val src = PragueLiveSource(OkHttpClient(), evictPool = { evictions++ },
            download = { nextBody ?: throw IOException("wedged") }, nowMs = { NOW })

        assertFetchFails { src.fetch() }       // first failure: banner, no eviction
        assertEquals(0, evictions)
        assertFetchFails { src.fetch() }
        assertEquals(1, evictions)

        nextBody = """{"vehicles":[]}"""
        src.fetch()
        nextBody = null
        assertFetchFails { src.fetch() }       // streak restarted
        assertEquals(1, evictions)
    }

    @Test
    fun `prague retains the last good list for two minutes`() = runTest {
        var now = NOW
        val src = PragueLiveSource(OkHttpClient(), evictPool = {},
            download = { """{"vehicles":[]}""" }, nowMs = { now })
        assertNull(src.retained())
        src.fetch()
        assertNotNull(src.retained())
        now += PragueLiveSource.RETAINED_MAX_AGE_MS + 1
        assertNull(src.retained())
    }

    // --- Release (#8 defect 2) ---

    @Test
    fun `interest shuts down only after the grace, and a re-acquire cancels it`() = runTest {
        val idle = mutableListOf<String>()
        val tracker = InterestTracker(this, graceMs = 120_000) { idle += it }

        tracker.acquire("brno")
        tracker.release("brno")
        advanceTimeBy(119_000)
        tracker.acquire("brno")                 // rotation / reopen inside the grace
        advanceTimeBy(10_000)
        assertTrue(idle.isEmpty())

        tracker.release("brno")
        advanceTimeBy(120_001)
        assertEquals(listOf("brno"), idle)
    }

    @Test
    fun `interest counts overlapping holders`() = runTest {
        val idle = mutableListOf<String>()
        val tracker = InterestTracker(this, graceMs = 1_000) { idle += it }
        tracker.acquire("praha")
        tracker.acquire("praha")
        tracker.release("praha")
        advanceTimeBy(5_000)
        assertTrue(idle.isEmpty())
        tracker.release("praha")
        tracker.release("praha")                // extra release never goes negative
        assertEquals(0, tracker.count("praha"))
        advanceTimeBy(1_001)
        assertEquals(listOf("praha"), idle)
    }

    @Test
    fun `cancelPending drops a scheduled shutdown`() = runTest {
        val idle = mutableListOf<String>()
        val tracker = InterestTracker(this, graceMs = 1_000) { idle += it }
        tracker.acquire("brno")
        tracker.release("brno")
        tracker.cancelPending()
        advanceUntilIdle()
        assertTrue(idle.isEmpty())
    }

    // --- Brno strict decode + streak (#8 defect 3) and join (defect 4) ---

    private class FakeSocket : WebSocket {
        var closed = false
        override fun request(): Request = Request.Builder().url(BrnoStream.STREAM_URL).build()
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true.also { closed = true }
        override fun cancel() { closed = true }
    }

    private class Harness(test: TestScope) {
        val sockets = mutableListOf<Pair<FakeSocket, WebSocketListener>>()
        val source = BrnoLiveStreamSource(
            openSocket = { l -> FakeSocket().also { sockets += it to l } },
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler)),
            nowMs = { NOW },
            log = {},
        )
        fun send(i: Int, text: String) = sockets[i].second.onMessage(sockets[i].first, text)
    }

    @Test
    fun `brno first message decoded strictly - garbage fails the fetch and closes the socket`() = runTest {
        val h = Harness(this)
        val fetch = async { runCatching { h.source.fetch() } }
        runCurrent()
        h.send(0, """{"unexpected":"schema"}""")
        advanceUntilIdle()
        assertTrue(fetch.await().exceptionOrNull() is IOException)
        assertTrue(h.sockets[0].first.closed)
    }

    @Test
    fun `brno silent socket fails after the first-message timeout`() = runTest {
        val h = Harness(this)
        val fetch = async { runCatching { h.source.fetch() } }
        advanceUntilIdle()
        assertTrue(fetch.await().exceptionOrNull() is IOException)
        assertTrue(h.sockets[0].first.closed)
    }

    @Test
    fun `brno good first message paints, a streak of 25 bad ones reconnects`() = runTest {
        val h = Harness(this)
        val fetch = async { h.source.fetch() }
        runCurrent()
        h.send(0, vehicle(1))
        advanceUntilIdle()
        assertEquals(listOf("1"), fetch.await().map { it.id })

        repeat(BrnoStream.DECODE_FAILURE_CUTOFF - 1) { h.send(0, "garbage") }
        h.send(0, vehicle(2))                   // a good one resets the streak
        repeat(BrnoStream.DECODE_FAILURE_CUTOFF - 1) { h.send(0, "garbage") }
        assertFalse(h.sockets[0].first.closed)
        assertEquals(setOf("1", "2"), h.source.fetch().map { it.id }.toSet())
        assertEquals(1, h.sockets.size)

        h.send(0, "garbage")                    // the 25th in a row
        assertTrue(h.sockets[0].first.closed)

        // The next poll reconnects; drifted schema fails it strictly (banner).
        val again = async { runCatching { h.source.fetch() } }
        runCurrent()
        assertEquals(2, h.sockets.size)
        h.send(1, "garbage")
        advanceUntilIdle()
        assertTrue(again.await().exceptionOrNull() is IOException)
    }

    @Test
    fun `brno fetch re-entered mid-connect joins it instead of reading an empty snapshot`() = runTest {
        val h = Harness(this)
        val first = async { h.source.fetch() }
        runCurrent()
        first.cancel()                          // rotation: the old poll loop dies mid-connect
        val second = async { h.source.fetch() }
        runCurrent()
        assertEquals(1, h.sockets.size)         // no second socket
        assertFalse(second.isCompleted)          // waiting for the burst, not "no vehicles"
        h.send(0, vehicle(7))
        advanceUntilIdle()
        assertEquals(listOf("7"), second.await().map { it.id })
        assertEquals(listOf("7"), h.source.retained()?.map { it.id })
    }

    @Test
    fun `brno shutdown closes the socket and the next fetch reconnects`() = runTest {
        val h = Harness(this)
        val fetch = async { h.source.fetch() }
        runCurrent()
        h.send(0, vehicle(1))
        advanceUntilIdle()
        fetch.await()
        h.source.shutdown()
        assertTrue(h.sockets[0].first.closed)
        assertNotNull(h.source.retained())      // the snapshot survives the socket
        val again = async { h.source.fetch() }
        runCurrent()
        assertEquals(2, h.sockets.size)
        h.send(1, vehicle(1))
        advanceUntilIdle()
        again.await()
    }

    @Test
    fun `brno connect cancelled by shutdown fails the waiting fetch with IOException`() = runTest {
        val h = Harness(this)
        val fetch = async { runCatching { h.source.fetch() } }
        runCurrent()
        h.source.shutdown()                     // ON_STOP / idle shutdown mid-connect
        advanceUntilIdle()
        assertTrue(fetch.await().exceptionOrNull() is IOException)
        assertTrue(h.sockets[0].first.closed)
    }

    @Test
    fun `brno cancelled caller propagates cancellation while the shared connect keeps going`() = runTest {
        val h = Harness(this)
        val caller = async { h.source.fetch() }
        runCurrent()
        caller.cancel()
        runCurrent()
        assertTrue(caller.isCancelled)
        assertFalse(h.sockets[0].first.closed)  // the connect itself was not torn down
        h.send(0, vehicle(3))
        advanceUntilIdle()
        assertEquals(listOf("3"), h.source.retained()?.map { it.id })
    }

    private suspend fun assertFetchFails(block: suspend () -> Unit) {
        try {
            block()
            fail("expected a failed fetch")
        } catch (e: IOException) {
            // expected
        }
    }

    companion object {
        const val NOW = 1_788_200_000_000L
        fun vehicle(id: Int) = """{"geometry":{"x":16.6,"y":49.2},"attributes":{"ID":$id,"VType":0,
            "Bearing":90.0,"LineName":"1","IsInactive":"false","TimeUpdated":$NOW}}"""
    }
}
