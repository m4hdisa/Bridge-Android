package io.github.m4hdisa.bridge.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.m4hdisa.bridge.R

/**
 * The Windows app's theme (src/renderer/src/styles.css): true neutral dark grays with the logo's
 * blue as the accent. `primary` is the logo blue, for fills with white text on them; on the dark
 * surfaces it is too dark for thin text and icons, so those use `brand`, the same hue lightened.
 */
object Bridge {
    val background = Color(0xFF0A0A0A)
    val foreground = Color(0xFFFAFAFA)
    val card = Color(0xFF131313)
    val popover = Color(0xFF171717)
    val primary = Color(0xFF023EE8)
    val primaryDeep = Color(0xFF012AB8)
    val primaryForeground = Color(0xFFF8FAFD)
    val brand = Color(0xFF649AFF)
    val secondary = Color(0xFF262626)
    val muted = Color(0xFF202020)
    val mutedForeground = Color(0xFFA1A1A1)
    val destructive = Color(0xFFED4A49)
    val success = Color(0xFF54C57A)
    val warning = Color(0xFFF4B93C)
    val info = Color(0xFF35C5DB)

    /** The navigation bar, a step darker than the page, like the Windows sidebar. */
    val sidebar = Color(0xFF060606)
    val sidebarAccent = Color(0xFF1B1B1B)

    /** Lines between and around surfaces: white at 9%. */
    val border = Color(0x17FFFFFF)

    /** Inputs and the frames of controls: white at 13%. */
    val input = Color(0x21FFFFFF)

    /** The connect key's light when connected. */
    val lightOn = Color(0xFF3CE074)
}

private val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

/** Geist has no Persian letters, so Persian gets Vazirmatn, which has Latin ones too. */
private val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

val GeistMono = FontFamily(Font(R.font.geist_mono_regular, FontWeight.Normal))

/** Tight letter spacing like the Windows app, for Latin only: in Persian it breaks the joined letters apart. */
private fun typography(family: FontFamily, persian: Boolean): Typography {
    fun tight(sp: Double) = if (persian) 0.sp else sp.sp
    val base = TextStyle(fontFamily = family, color = Bridge.foreground, letterSpacing = tight(-0.08))
    return Typography(
        headlineLarge = base.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = tight(-0.6)),
        headlineMedium = base.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = tight(-0.5)),
        titleLarge = base.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = base.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Bridge.mutedForeground),
        labelLarge = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    val persian = LocalConfiguration.current.locales[0].language == "fa"
    val type = remember(persian) { typography(if (persian) Vazirmatn else Geist, persian) }
    val colors = darkColorScheme(
        primary = Bridge.primary,
        onPrimary = Bridge.primaryForeground,
        secondary = Bridge.secondary,
        onSecondary = Bridge.foreground,
        background = Bridge.background,
        onBackground = Bridge.foreground,
        surface = Bridge.card,
        onSurface = Bridge.foreground,
        surfaceVariant = Bridge.muted,
        onSurfaceVariant = Bridge.mutedForeground,
        surfaceContainer = Bridge.popover,
        surfaceContainerHigh = Bridge.popover,
        surfaceContainerLow = Bridge.card,
        outline = Bridge.input,
        outlineVariant = Bridge.border,
        error = Bridge.destructive,
        inverseSurface = Bridge.foreground,
        inverseOnSurface = Bridge.background,
    )
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
