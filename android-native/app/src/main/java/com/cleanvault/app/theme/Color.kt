package com.cleanvault.app.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// 3D Claymorphic Palette
val ClayAppBg = Color(0xFFF4F2FA)
val ClayPageBg = Color(0xFFEDE9F6)

val ClayCasingTop = Color(0xFFA855F7)
val ClayCasingMid = Color(0xFF7C3AED)
val ClayCasingBottom = Color(0xFF6D28D9)

val ClayDrawerTop = Color(0xFFB57BFB)
val ClayDrawerMid = Color(0xFF8B5CF6)
val ClayDrawerBottom = Color(0xFF7C3AED)

val ClaySlotBg = Color(0xFF4C1D95)

val ClayScanTop = Color(0xFF8B5CF6)
val ClayScanBottom = Color(0xFF6D28D9)

val NeonAmberTop = Color(0xFFFFF3BF)
val NeonAmberMid = Color(0xFFFFB703)
val NeonAmberBottom = Color(0xFFFB8500)

val TextDark = Color(0xFF1E1B4B)
val TextSubtle = Color(0xFF64748B)
val TextLavender = Color(0xFFDDD6FE)
val CardWhite = Color(0xFFFFFFFF)
val PillLavender = Color(0xFFDDD6FE)
val PurpleDark = Color(0xFF6D28D9)
val PurpleAccent = Color(0xFF8B5CF6)
val PurpleDeep = Color(0xFF4C1D95)

// Gradients
val ClayCabinetGradient = Brush.verticalGradient(
    listOf(ClayCasingTop, ClayCasingMid, ClayCasingBottom)
)
val ClayDrawerGradient = Brush.verticalGradient(
    listOf(ClayDrawerTop, ClayDrawerMid, ClayDrawerBottom)
)
val ClayScanCardGradient = Brush.verticalGradient(
    listOf(ClayScanTop, ClayScanBottom)
)
val NeonAmberHandle = Brush.verticalGradient(
    listOf(NeonAmberTop, NeonAmberMid, NeonAmberBottom)
)

// Tokens for compatibility
val BgBase = ClayAppBg
val SurfaceColor = CardWhite
val SurfaceCard = CardWhite
val SurfaceRaised = Color(0xFFEDE9F6)
val SurfaceHover = Color(0xFFE2DCF0)
val BorderColor = Color(0xFFE2DCF0)
val BorderHighlight = Color(0xFFDDD6FE)

val AccentColor = PurpleDark
val AccentMid = PurpleAccent
val WarningColor = Color(0xFFF50ACE)
val AmberGold = Color(0xFFFB8500)
val EmeraldGreen = Color(0xFF10B981)
val RoseRed = Color(0xFFEF4444)

val TextMain = TextDark
val TextMuted = TextSubtle
val TextFaint = Color(0xFF94A3B8)

