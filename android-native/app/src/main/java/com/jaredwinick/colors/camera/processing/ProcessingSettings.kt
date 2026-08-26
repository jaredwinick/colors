package com.jaredwinick.colors.camera.processing

import com.jaredwinick.colors.camera.config.AppConfiguration

data class ProcessingSettings(
    val maxImageDimension: Int,
    val jpegQuality: Int,
    val paletteColors: Int,
    val paletteAnalysisDimension: Int,
)

fun AppConfiguration.processingSettings(): ProcessingSettings = ProcessingSettings(
    maxImageDimension = maxImageDimension,
    jpegQuality = jpegQuality,
    paletteColors = paletteColors,
    paletteAnalysisDimension = paletteAnalysisDimension,
)
