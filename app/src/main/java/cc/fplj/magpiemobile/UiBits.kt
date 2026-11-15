package cc.fplj.magpiemobile

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cc.fplj.magpiemobile.theme.UiSpacing

/**
 * 原生适配技能的 Card / Empty State / 导航图标：暖色面板、细边框、统一间距。
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ContentCard(modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}

@Composable
internal fun ContentCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(UiSpacing.card), verticalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
            content()
        }
    }
}

@Composable
internal fun PageHeading(title: String, description: String, eyebrow: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
        eyebrow?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyState(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = UiSpacing.large),
        verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Full-screen dialogs draw edge to edge; keep system bar icons readable in either theme. */
@Composable
internal fun FullScreenDialogSystemBars() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

/** 单色线框图标以 Icon 的主题 tint 着色，无额外图标依赖。 */
internal enum class AppSymbol(val path: String) {
    ADD("M12,5 L12,19 M5,12 L19,12"),
    GATEWAY("M5,3 L19,3 L19,9 L5,9 Z M5,15 L19,15 L19,21 L5,21 Z M8,6 L8.1,6 M8,18 L8.1,18 M12,9 L12,15"),
    PROVIDERS("M4,4 L10,4 L10,10 L4,10 Z M14,4 L20,4 L20,10 L14,10 Z M4,14 L10,14 L10,20 L4,20 Z M14,14 L20,14 L20,20 L14,20 Z"),
    CHAT("M4,4 L20,4 L20,16 L10,16 L5,20 L5,16 L4,16 Z M8,8 L16,8 M8,12 L13,12"),
    MORE("M4,6 L20,6 M4,12 L20,12 M4,18 L20,18 M8,4 L8,8 M16,10 L16,14 M10,16 L10,20"),
    BACK("M14,5 L7,12 L14,19 M7,12 L21,12"),
    NEXT("M9,5 L16,12 L9,19"),
    KEY("M10,9 A4,4 0,1 1,2,9 A4,4 0,1 1,10,9 M10,9 L21,9 L21,13 M17,9 L17,12"),
    USAGE("M4,19 L4,5 M4,19 L21,19 M8,15 L8,11 M13,15 L13,7 M18,15 L18,3"),
    ROUTE("M5,4 L5,14 Q5,19 10,19 L19,19 M5,9 L15,9 Q19,9 19,4 M16,6 L19,3 L22,6 M16,16 L19,19 L16,22"),
    PLUGIN("M8,3 L8,8 M16,3 L16,8 M5,8 L19,8 L19,11 Q19,17 12,17 Q5,17 5,11 Z M12,17 L12,22"),
    DEVICE("M7,2 L17,2 L17,22 L7,22 Z M10,18 L14,18"),
    MODELS("M12,2 L22,7 L12,12 L2,7 Z M2,12 L12,17 L22,12 M2,17 L12,22 L22,17"),
    SEND("M12,20 L12,4 M5,11 L12,4 L19,11"),
    DOWN("M5,9 L12,16 L19,9"),
    UP("M5,15 L12,8 L19,15"),
}

@Composable
internal fun AppIcon(symbol: AppSymbol, modifier: Modifier = Modifier, description: String? = null) {
    val vector = remember(symbol) {
        ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString(symbol.path).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
        }.build()
    }
    Icon(vector, contentDescription = description, modifier = modifier.size(UiSpacing.icon))
}
