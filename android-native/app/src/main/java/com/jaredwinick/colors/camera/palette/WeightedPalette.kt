package com.jaredwinick.colors.camera.palette

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class WeightedPaletteColor(val hex: String, val weight: Double)

data class WeightedPalette(val colors: List<WeightedPaletteColor>) {
    fun requireValid(): WeightedPalette {
        require(colors.size in MIN_COLORS..MAX_COLORS) {
            "Palette must contain $MIN_COLORS-$MAX_COLORS colors"
        }
        colors.forEachIndexed { index, color ->
            require(HEX.matches(color.hex)) { "Palette color $index must be uppercase #RRGGBB" }
            require(color.weight.isFinite() && color.weight > 0.0) {
                "Palette weight $index must be positive and finite"
            }
        }
        require(kotlin.math.abs(colors.sumOf { it.weight } - 1.0) <= 0.000001) {
            "Palette weights must total 1.0"
        }
        require(
            colors.zipWithNext().all { (first, second) ->
                first.weight > second.weight ||
                    (first.weight == second.weight && first.hex <= second.hex)
            },
        ) { "Palette must use descending weight and hexadecimal tie-break order" }
        return this
    }

    fun toJson(): String = JSONArray().apply {
        requireValid().colors.forEach { color ->
            put(JSONObject().apply {
                put("hex", color.hex)
                put("weight", color.weight)
            })
        }
    }.toString()

    companion object {
        const val MIN_COLORS = 3
        const val MAX_COLORS = 10
        private val HEX = Regex("#[0-9A-F]{6}")

        fun hex(rgb: Int): String = String.format(
            Locale.US,
            "#%02X%02X%02X",
            rgb shr 16 and 0xff,
            rgb shr 8 and 0xff,
            rgb and 0xff,
        )
    }
}

