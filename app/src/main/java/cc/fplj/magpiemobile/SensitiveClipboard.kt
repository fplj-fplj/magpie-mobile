package cc.fplj.magpiemobile

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/** A local clipboard wrapper that marks sensitive text before handing it to Android. */
@Composable
internal fun rememberSensitiveClipboardManager(): ClipboardManager {
    val delegate = LocalClipboardManager.current
    return remember(delegate) {
        object : ClipboardManager by delegate {
            override fun setText(annotatedString: AnnotatedString) {
                val clip = ClipData.newPlainText("Sensitive text", annotatedString.text)
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
                delegate.setClip(ClipEntry(clip))
            }
        }
    }
}
