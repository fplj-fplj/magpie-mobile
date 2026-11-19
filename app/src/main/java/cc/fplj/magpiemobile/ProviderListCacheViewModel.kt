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
    private var owner: Long? = null
    private var ownerGeneration = 0L
    private var generation = 0L
    private var cached: ProviderListSnapshot? = null
    private var inflight: Deferred<ProviderListSnapshot>? = null
    private val writes = mutableMapOf<Long, MutableSet<ActiveWrite>>()

    interface WriteBarrier {
        /** Call only after the actual write transport has exited, including cancellation. */
        fun finish()
    }

    /** Register before setting the recovery marker and before dispatching any provider write. */
    suspend fun beginWrite(owner: Long): WriteBarrier = mutex.withLock {
        selectOwner(owner)
        invalidateRead()
        ActiveWrite().also { writes.getOrPut(owner) { mutableSetOf() }.add(it) }
    }

    suspend fun read(
        owner: Long,
        force: Boolean = false,
        fetch: suspend () -> ProviderListSnapshot,
    ): ProviderListSnapshot = readAfterWrites(owner, force, recovery = false, fetch = fetch)

    /** Never confirms an unknown write using a cached value or a GET started before that write. */
    suspend fun readForRecovery(
        owner: Long,
        fetch: suspend () -> ProviderListSnapshot,
    ): ProviderListSnapshot = readAfterWrites(owner, force = true, recovery = true, fetch = fetch)

    private suspend fun readAfterWrites(
        owner: Long,
        force: Boolean,
        recovery: Boolean,
        fetch: suspend () -> ProviderListSnapshot,
    ): ProviderListSnapshot {
        val selectedOwnerGeneration = mutex.withLock {
            selectOwner(owner)
            ownerGeneration
        }
        var selection: ReadSelection? = null
        while (selection == null) {
            currentCoroutineContext().ensureActive()
            val blockers = mutex.withLock {
                ensureOwner(owner, selectedOwnerGeneration)
                pruneWrites()
                val pendingWrites = writes[owner]?.toList().orEmpty()
                if (pendingWrites.isNotEmpty()) {
                    pendingWrites
                } else {
                    // Recheck writes and establish the read generation under the same lock.
                    if (recovery) invalidateRead()
                    selection = selectRead(owner, force, fetch)
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
            ensureOwner(owner, selectedOwnerGeneration)
            if (generation != selected.generation) {
                throw CancellationException("Provider read was superseded")
            }
            result
        }
    }

    /** Called only under mutex, after all writes for this owner have exited. */
    private fun selectRead(
        owner: Long,
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
                        if (this@ProviderListCacheViewModel.owner == owner &&
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

    private fun selectOwner(owner: Long) {
        // GatewayRuntime attach/stoppedWithoutService allocate monotonically increasing owners.
        // This VM shares that process lifetime: a late old operation must never select backwards.
        val latestOwner = this.owner
        if (latestOwner != null && owner < latestOwner) {
            throw CancellationException("Provider session was superseded")
        }
        pruneWrites()
        if (this.owner != owner) {
            this.owner = owner
            ownerGeneration++
            invalidateRead()
        }
    }

    private fun ensureOwner(owner: Long, selectedOwnerGeneration: Long) {
        if (this.owner != owner || ownerGeneration != selectedOwnerGeneration) {
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
