package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestCancellationTest {
    @Test fun successfulCompletionDisposesWatcherWithoutCancellingTheControl() = runBlocking(Dispatchers.IO) {
        val control = ChatRequestControl()

        assertEquals("saved", withChatRequestCancellation(control) { "saved" })

        // coroutineScope has joined the disposed watcher before returning. A normal result must
        // leave the signal active, including when the helper is entered again on the same Job.
        control.ensureActive()
        assertEquals(42, withChatRequestCancellation(control) { 42 })
        control.ensureActive()
    }

    @Test fun parentCancellationStopsTheControlBeforeTheSynchronousBlockCanReturn() = runBlocking {
        val control = ChatRequestControl()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockReturned = AtomicBoolean()
        val request = launch(Dispatchers.IO) {
            withChatRequestCancellation(control) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                blockReturned.set(true)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            request.cancel()
            withTimeout(5000) {
                while (true) {
                    try { control.ensureActive() }
                    catch (_: CancellationException) { break }
                    delay(1)
                }
            }
            assertFalse(blockReturned.get())
            assertTrue(request.isCancelled)
        } finally {
            release.countDown()
            withTimeout(5000) { request.join() }
        }
        assertTrue(blockReturned.get())
    }
}
