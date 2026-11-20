package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Bridge Job cancellation to synchronous HTTP I/O, including streams with no text callbacks. */
internal suspend fun <T> withChatRequestCancellation(control: ChatRequestControl, block: () -> T): T {
    // Observe the caller, not the child scope: a business failure also cancels coroutineScope,
    // but must leave a captured session usable for error handling and read-only reconciliation.
    val requestContext = currentCoroutineContext()
    return coroutineScope {
        val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                // A normal completion or business failure only disposes this watcher.
                if (!requestContext.isActive) control.cancel()
            }
        }
        try {
            requestContext.ensureActive()
            control.ensureActive()
            block()
        } finally {
            cancellationWatcher.cancel()
        }
    }
}
