package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.ProviderListSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Activity-owned safe metadata cache; Go remains the only provider configuration source.
 * No file writes, credentials, raw JSON, discovery, mutation, startup or inference operations.
 * The fetch callback must return the already-sanitized snapshot from getProviders().
 */
class ProviderListCacheViewModel(
    private val readScope: CoroutineScope? = null,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutex = Mutex()
    private var session: ProviderSessionKey? = null
    private var sessionGeneration = 0L
    private var generation = 0L
    private var cached: ProviderListSnapshot? = null
    private var inflight: Deferred<ProviderListSnapshot>? = null
    private val writes = mutableMapOf<Long, MutableSet<ActiveWrite>>()

    interface WriteBarrier {
        /** Call only after the actual write transport has exited, including cancellation. */
        fun finish()
    }

    /** Register before setting the recovery marker and before dispatching any provider write. */
    suspend fun beginWrite(session: ProviderSessionKey): WriteBarrier = mutex.withLock {
        selectSession(session)
        invalidateRead()
        ActiveWrite().also { writes.getOrPut(session.owner) { mutableSetOf() }.add(it) }
    }

    suspend fun read(
        session: ProviderSessionKey,
        force: Boolean = false,
        fetch: suspend () -> ProviderListSnapshot,
    ): ProviderListSnapshot = readAfterWrites(session, force, recovery = false, fetch = fetch)

    /** Never confirms an unknown write using a cached value or a GET started before that write. */
    suspend fun readForRecovery(
        session: ProviderSessionKey,
        fetch: suspend () -> ProviderListSnapshot,
    ): ProviderListSnapshot = readAfterWrites(session, force = true, recovery = true, fetch = fetch)

    private suspend fun readAfterWrites(
        session: ProviderSessionKey,
        force: Boolean,
        recovery: Boolean,
        fetch: suspend () -> ProviderListSnapshot,
    ): ProviderListSnapshot {
        val selectedSessionGeneration = mutex.withLock {
            selectSession(session)
            sessionGeneration
        }
        var selection: ReadSelection? = null
        while (selection == null) {
            currentCoroutineContext().ensureActive()
            val blockers = mutex.withLock {
                ensureSession(session, selectedSessionGeneration)
                pruneWrites()
                // A reconnected epoch still waits for the previous epoch's actual HTTP exit.
                val pendingWrites = writes[session.owner]?.toList().orEmpty()
                if (pendingWrites.isNotEmpty()) {
                    pendingWrites
                } else {
                    // Recheck writes and establish the read generation under the same lock.
                    if (recovery) invalidateRead()
                    selection = selectRead(session, force, fetch)
                    emptyList()
                }
            }
            // A canceled observer never completes the write signal or cancels another observer.
            blockers.forEach { it.finished.await() }
        }
        val selected = checkNotNull(selection)
        selected.task?.start()
        val result = selected.task?.await() ?: checkNotNull(selected.value)
        return mutex.withLock {
            ensureSession(session, selectedSessionGeneration)
            if (generation != selected.generation) {
                throw CancellationException("Provider read was superseded")
            }
            result
        }
    }

    /** Called only under mutex, after all writes for this owner have exited. */
    private fun selectRead(
        session: ProviderSessionKey,
        force: Boolean,
        fetch: suspend () -> ProviderListSnapshot,
    ): ReadSelection {
        // Join refresh as well as automatic reads; caller cancellation never clears this slot.
        inflight?.let {
            if (!it.isCompleted) return ReadSelection(generation, task = it)
            // Also handles a VM scope cancelled before a lazy Deferred ever entered its body.
            inflight = null
        }
        if (!force) cached?.takeIf { !it.hasFileError }?.let {
            return ReadSelection(generation, value = it)
        }
        // A failed explicit refresh must not leave an old healthy snapshot reusable.
        cached = null
        val readGeneration = generation
        lateinit var pending: Deferred<ProviderListSnapshot>
        pending = (readScope ?: viewModelScope).async(readDispatcher, start = CoroutineStart.LAZY) {
            var result: ProviderListSnapshot? = null
            try {
                fetch().also { result = it }
            } finally {
                val healthy = result?.takeIf { isActive && !it.hasFileError }
                // Completion owns cleanup, even when every Composable await was cancelled.
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (this@ProviderListCacheViewModel.session == session &&
                            generation == readGeneration && inflight === pending) {
                            cached = healthy
                            inflight = null
                        }
                    }
                }
            }
        }
        inflight = pending
        return ReadSelection(readGeneration, task = pending)
    }

    private fun selectSession(session: ProviderSessionKey) {
        // GatewayRuntime owners and NativeCoreSession epochs increase within this VM's process.
        // A late old operation must never select an older owner or reconnect epoch.
        val latest = this.session
        if (latest != null && (session.owner < latest.owner ||
                (session.owner == latest.owner && session.epoch < latest.epoch))) {
            throw CancellationException("Provider session was superseded")
        }
        pruneWrites()
        if (this.session != session) {
            this.session = session
            sessionGeneration++
            invalidateRead()
        }
    }

    private fun ensureSession(session: ProviderSessionKey, selectedSessionGeneration: Long) {
        if (this.session != session || sessionGeneration != selectedSessionGeneration) {
            throw CancellationException("Provider session changed")
        }
    }

    private fun pruneWrites() {
        writes.values.forEach { active -> active.removeAll { it.finished.isCompleted } }
        writes.entries.removeAll { it.value.isEmpty() }
    }

    private fun invalidateRead() {
        generation++
        cached = null
        inflight = null
    }

    /** Await invalidation before a save/refresh read, so it cannot join an older generation. */
    suspend fun invalidate() = mutex.withLock { invalidateRead() }

    private class ActiveWrite : WriteBarrier {
        val finished = CompletableDeferred<Unit>()
        override fun finish() {
            finished.complete(Unit)
        }
    }

    private data class ReadSelection(
        val generation: Long,
        val task: Deferred<ProviderListSnapshot>? = null,
        val value: ProviderListSnapshot? = null,
    )
}
