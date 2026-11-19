package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Bridge Job cancellation to synchronous HTTP I/O, including streams with no text callbacks. */
internal suspend fun <T> withChatRequestCancellation(control: ChatRequestControl, block: () -> T): T = coroutineScope {
    val requestContext = coroutineContext
    val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            // A normal completion also disposes this watcher; it must not cancel the request.
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
