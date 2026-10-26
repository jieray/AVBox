package com.github.avbox.osc.ui.theme

import com.github.avbox.osc.R
import com.materialkolor.PaletteStyle

object ThemeSource {
    const val SYSTEM = 0

    const val CUSTOM = 1
}

object ThemeMode {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

data class ThemeConfig(
    val source: Int,
    val mode: Int,
    val seedArgb: Int,
    val style: PaletteStyle,
    val pureBlack: Boolean,
)

// 预设种子色按感知均匀重新取色：
// 色相沿色环均匀铺开（25/62/98/152/198/252/298/340），
// 用 OKLCH 反解到 sRGB 并保持相对亮度一致（≈0.15），
// 这样八个色块摆在一起轻重相当、不会有的刺眼有的发闷，
// 彩度统一压在中等区间，长时间看视频界面不容易疲劳。
private val SeedRedArgb: Int = 0xFFEA6A64.toInt()

val DefaultSeedArgb: Int = SeedRedArgb

val DefaultPaletteStyle: PaletteStyle = PaletteStyle.TonalSpot

val PresetSeeds: List<Pair<Int, Int>> = listOf(
    R.string.theme_seed_red to SeedRedArgb,
    R.string.theme_seed_orange to 0xFFE08C38.toInt(),
    R.string.theme_seed_yellow to 0xFFBFAB49.toInt(),
    R.string.theme_seed_green to 0xFF3BBA6B.toInt(),
    R.string.theme_seed_cyan to 0xFF46B6BA.toInt(),
    R.string.theme_seed_blue to 0xFF4AA2FF.toInt(),
    R.string.theme_seed_purple to 0xFFB48EFD.toInt(),
    R.string.theme_seed_pink to 0xFFE176C1.toInt(),
)

val PaletteStyles: List<Pair<PaletteStyle, Int>> = listOf(
    PaletteStyle.TonalSpot to R.string.theme_style_tonal_spot,
    PaletteStyle.Vibrant to R.string.theme_style_vibrant,
    PaletteStyle.Expressive to R.string.theme_style_expressive,
    PaletteStyle.Neutral to R.string.theme_style_neutral,
    PaletteStyle.Monochrome to R.string.theme_style_monochrome,
    PaletteStyle.Fidelity to R.string.theme_style_fidelity,
    PaletteStyle.Content to R.string.theme_style_content,
    PaletteStyle.Rainbow to R.string.theme_style_rainbow,
    PaletteStyle.FruitSalad to R.string.theme_style_fruit_salad,
)
