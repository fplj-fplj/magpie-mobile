package cc.fplj.magpiemobile.core

import java.io.Closeable
import java.io.IOException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatRequestControlTest {
    @Test
    fun unregisteringAnActiveActionKeepsItOutOfTheNextCancellation() {
        val control = ChatRequestControl()
        val removedCalls = AtomicInteger()
        val remaining = CountDownLatch(1)
        val removed = control.register { removedCalls.incrementAndGet() }
        val registered = control.register { remaining.countDown() }
        removed.close()
        removed.close()

        control.cancel()

        assertTrue(remaining.await(2, TimeUnit.SECONDS))
        assertEquals(0, removedCalls.get())
        assertFailsWith<CancellationException> { control.ensureActive() }
        registered.close()
    }

    @Test
    fun stoppedControlRejectsNewRegistrationsWithItsOriginalStopReason() {
        val cancelled = ChatRequestControl()
        cancelled.cancel()
        val calls = AtomicInteger()
        assertFailsWith<CancellationException> { cancelled.register { calls.incrementAndGet() } }

        val expired = ChatRequestControl()
        expired.deadlineExceeded()
        val failure = assertFailsWith<StreamingChatException> { expired.register { calls.incrementAndGet() } }

        assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, failure.failure)
        assertEquals(0, calls.get())
    }

    @Test
    fun cancellationRemainsAuthoritativeWhenDeadlineArrivesLater() {
        val control = ChatRequestControl()
        val calls = AtomicInteger()
        val stopped = CountDownLatch(1)
        control.register { calls.incrementAndGet(); stopped.countDown() }.use {
            control.cancel()
            control.deadlineExceeded()
            control.cancel()

            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            repeat(3) { assertFailsWith<CancellationException> { control.ensureActive() } }
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun deadlineRemainsAuthoritativeWhenCancellationArrivesLater() {
        val control = ChatRequestControl()
        val stopped = CountDownLatch(1)
        control.register { stopped.countDown() }.use {
            control.startDeadline(Duration.ofMillis(100)).use {
                assertTrue(stopped.await(3, TimeUnit.SECONDS))
                control.cancel()

                repeat(3) {
                    val failure = assertFailsWith<StreamingChatException> { control.ensureActive() }
                    assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, failure.failure)
                    assertEquals("", failure.partialText)
                    assertEquals("", failure.partialReasoning)
                }
            }
        }
    }

    @Test
    fun closingDeadlinePreventsItsScheduledOrMonotonicCheckFromStoppingTheControl() {
        val control = ChatRequestControl()
        val stopped = CountDownLatch(1)
        control.register { stopped.countDown() }.use {
            val deadline = control.startDeadline(Duration.ofMillis(250))
            deadline.close()
            deadline.close()

            assertFalse(stopped.await(400, TimeUnit.MILLISECONDS))
            control.ensureActive()
        }
    }

    @Test
    fun closingOneDeadlineLeavesAnIndependentDeadlineEnforced() {
        val control = ChatRequestControl()
        val stopped = CountDownLatch(1)
        control.register { stopped.countDown() }.use {
            control.startDeadline(Duration.ofMillis(800)).use {
                control.startDeadline(Duration.ofMillis(200)).close()

                assertFalse(stopped.await(350, TimeUnit.MILLISECONDS))
                control.ensureActive()
                assertTrue(stopped.await(3, TimeUnit.SECONDS))
                val failure = assertFailsWith<StreamingChatException> { control.ensureActive() }
                assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, failure.failure)
            }
        }
    }

    @Test
    fun deadlineScopeRejectsInvalidDurationsWithoutStoppingTheControl() {
        val control = ChatRequestControl()
        for (duration in listOf(Duration.ZERO, Duration.ofMillis(-1), Duration.ofDays(2))) {
            assertFailsWith<IllegalArgumentException> { control.startDeadline(duration) }
            control.ensureActive()
        }
    }

    @Test
    fun cancelReturnsEvenWhenARegisteredDisconnectIsBlocked() {
        val control = ChatRequestControl()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val registration = control.register {
            entered.countDown()
            try { release.await(5, TimeUnit.SECONDS) } finally { completed.countDown() }
        }
        try {
            val cancelled = worker.submit(Callable { control.cancel() })

            cancelled.get(2, TimeUnit.SECONDS)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(1L, completed.count)
            assertFailsWith<CancellationException> { control.ensureActive() }
        } finally {
            release.countDown()
            registration.close()
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            assertTrue(completed.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun failingDisconnectDoesNotBlockOtherDisconnectsOrExposeItsException() {
        val control = ChatRequestControl()
        val failed = CountDownLatch(1)
        val healthy = CountDownLatch(1)
        control.register {
            failed.countDown()
            throw IOException("fixture-sensitive-token")
        }.use {
            control.register { healthy.countDown() }.use {
                control.cancel()

                assertTrue(failed.await(2, TimeUnit.SECONDS))
                assertTrue(healthy.await(2, TimeUnit.SECONDS))
                val failure = assertFailsWith<CancellationException> { control.ensureActive() }
                assertEquals("Chat request cancelled", failure.message)
                assertFalse(failure.stackTraceToString().contains("fixture-sensitive-token"))
            }
        }
    }

    @Test
    fun simultaneousRegistrationAndCancellationNeverLeaveAnAcceptedCallRunning() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val control = ChatRequestControl()
                val start = CountDownLatch(1)
                val disconnected = CountDownLatch(1)
                val registration = workers.submit(Callable<Closeable?> {
                    start.await()
                    try { control.register { disconnected.countDown() } }
                    catch (_: CancellationException) { null }
                })
                val cancelling = workers.submit(Callable {
                    start.await()
                    control.cancel()
                })
                start.countDown()
                val accepted = registration.get(3, TimeUnit.SECONDS)
                cancelling.get(3, TimeUnit.SECONDS)

                assertFailsWith<CancellationException> { control.ensureActive() }
                if (accepted != null) {
                    assertTrue(disconnected.await(2, TimeUnit.SECONDS))
                    accepted.close()
                }
            }
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun racingStopReasonsAlwaysKeepTheWinningReason() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(50) {
                val control = ChatRequestControl()
                val start = CountDownLatch(1)
                val cancel = workers.submit(Callable { start.await(); control.cancel() })
                val expire = workers.submit(Callable { start.await(); control.deadlineExceeded() })
                start.countDown()
                cancel.get(3, TimeUnit.SECONDS)
                expire.get(3, TimeUnit.SECONDS)
                val first = runCatching { control.ensureActive() }.exceptionOrNull()
                assertTrue(first is CancellationException || first is StreamingChatException)

                repeat(5) {
                    val next = runCatching { control.ensureActive() }.exceptionOrNull()
                    assertEquals(first!!::class, next!!::class)
                    if (next is StreamingChatException) {
                        assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, next.failure)
                    } else {
                        assertIs<CancellationException>(next)
                    }
                }
            }
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
