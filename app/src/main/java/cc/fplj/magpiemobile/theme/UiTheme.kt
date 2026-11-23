package cc.fplj.magpiemobile.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.fplj.magpiemobile.R

/**
 * Anthropic 风格设计令牌（anthropic-style-frontend-cn 技能 · Compose 适配 · 工具优先模式）。
 *
 * 设计语言：技术性 × 人文温度 —— 暖米色大地调基底、衬线/无衬线混排、
 * 唯一强调色陶土橙。首页采用编辑叙事排版，二级管理界面保留功能密度。
 * 字体：霞鹜文楷屏幕阅读版（全界面，SIL OFL），顶部品牌文字保留 Poppins。
 * 许可文本随包分发在 assets/licenses/。全应用零硬编码颜色，统一经 MaterialTheme 取用。
 */

// —— 字体族 ——
/** Screen already uses heavier WenKai outlines. Bold emphasis is synthesized from that face. */
internal val Reader = FontFamily(Font(R.font.lxgw_wenkai_screen, FontWeight.Normal))
internal val BrandFont = FontFamily(
    Font(R.font.poppins, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

// —— 亮色令牌 ——
private val LightColors = lightColorScheme(
    primary = Color(0xFF9C432D),              // 深陶土色：在暖纸表面上保持至少 4.5:1
    onPrimary = Color(0xFFFFF8F2),             // 深陶土底上的浅暖白字
    primaryContainer = Color(0xFFEBD3C5),
    onPrimaryContainer = Color(0xFF3E2418),
    secondary = Color(0xFF7A6F5D),            // 暖灰褐：次级动作
    onSecondary = Color(0xFFFCFAF5),
    secondaryContainer = Color(0xFFE7E0D2),
    onSecondaryContainer = Color(0xFF2A261E),
    tertiary = Color(0xFF5C7265),             // 鼠尾草绿：图表/次强调
    onTertiary = Color(0xFFF5F3EC),
    error = Color(0xFFA83A30),                // 深砖红：正文与边框均满足对比度要求
    onError = Color(0xFFFFF8F2),
    errorContainer = Color(0xFFF1D4CF),
    onErrorContainer = Color(0xFF3B1210),
    background = Color(0xFFECE9E0),           // 页面基底：暖米色，不用纯白
    onBackground = Color(0xFF141413),
    surface = Color(0xFFECE9E0),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFE1DDD1),
    onSurfaceVariant = Color(0xFF6B6860),
    surfaceContainerLowest = Color(0xFFF7F5EE),
    surfaceContainerLow = Color(0xFFF5F3EC),  // 卡片/面板抬升色
    surfaceContainer = Color(0xFFF0EDE3),
    surfaceContainerHigh = Color(0xFFECE8DD),
    surfaceContainerHighest = Color(0xFFE6E2D6),
    inverseSurface = Color(0xFF141413),       // 深色反相区块
    inverseOnSurface = Color(0xFFECE9E0),
    inversePrimary = Color(0xFFE08A69),
    outline = Color(0xFFBFB9AC),
    outlineVariant = Color(0xFFD8D4C8),
    scrim = Color(0xFF141413),
)

// —— 暗色令牌 ——
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE08A69),
    onPrimary = Color(0xFF3B1F12),
    primaryContainer = Color(0xFF6E3B26),
    onPrimaryContainer = Color(0xFFF2D9CC),
    secondary = Color(0xFFB5AE9D),
    onSecondary = Color(0xFF2A261E),
    secondaryContainer = Color(0xFF3E3A31),
    onSecondaryContainer = Color(0xFFE7E0D2),
    tertiary = Color(0xFFA4BCAE),
    onTertiary = Color(0xFF1C2822),
    error = Color(0xFFE0645A),
    onError = Color(0xFF2A1210),
    errorContainer = Color(0xFF5E2420),
    onErrorContainer = Color(0xFFF1D4CF),
    background = Color(0xFF141413),
    onBackground = Color(0xFFECE9E0),
    surface = Color(0xFF141413),
    onSurface = Color(0xFFECE9E0),
    surfaceVariant = Color(0xFF2A2825),
    onSurfaceVariant = Color(0xFFA9A398),
    surfaceContainerLowest = Color(0xFF191816),
    surfaceContainerLow = Color(0xFF1E1C1A),
    surfaceContainer = Color(0xFF23211E),
    surfaceContainerHigh = Color(0xFF292722),
    surfaceContainerHighest = Color(0xFF302D27),
    inverseSurface = Color(0xFFECE9E0),
    inverseOnSurface = Color(0xFF141413),
    inversePrimary = Color(0xFFD97757),
    outline = Color(0xFF57534A),
    outlineVariant = Color(0xFF3A3731),
    scrim = Color(0xFF0A0A09),
)

// —— 排版：所有 Material 文本角色统一使用屏幕阅读版，标题与操作文字加强字重 ——
internal fun readerTypography() = Typography(
    displayLarge = readerStyle(46, 56, FontWeight.Bold),
    displayMedium = readerStyle(38, 48, FontWeight.Bold),
    displaySmall = readerStyle(32, 42, FontWeight.Bold),
    headlineLarge = readerStyle(30, 40, FontWeight.Bold),
    headlineMedium = readerStyle(26, 36, FontWeight.Bold),
    headlineSmall = readerStyle(22, 32, FontWeight.Bold),
    titleLarge = readerStyle(20, 30, FontWeight.Bold),
    titleMedium = readerStyle(17, 26, FontWeight.Bold),
    titleSmall = readerStyle(15, 24, FontWeight.Bold),
    bodyLarge = readerStyle(17, 30),
    bodyMedium = readerStyle(16, 28),
    bodySmall = readerStyle(14, 24),
    labelLarge = readerStyle(15, 24, FontWeight.Bold),
    labelMedium = readerStyle(13, 20, FontWeight.Bold),
    labelSmall = readerStyle(12, 18),
)

private fun readerStyle(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = Reader, fontWeight = weight, fontSynthesis = FontSynthesis.Weight,
    fontSize = size.sp, lineHeight = height.sp,
)

// —— 圆角：克制的中等圆角，管理界面保持信息密度 ——
private val AnthropicShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 应用统一主题入口：Anthropic 风格令牌 + 明暗双模式。 */
@Composable
fun MagpieTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val typography = remember { readerTypography() }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = typography,
        shapes = AnthropicShapes,
        content = content,
    )
}
