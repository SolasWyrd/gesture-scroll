package com.arti.gesturescroll

import android.graphics.Bitmap
import kotlin.math.roundToInt

internal object LowLightFrameEnhancer {
    private const val START_ENHANCING_LUMA = 82f
    private const val MAX_GAIN = 2.4f
    private const val SAMPLE_STEP = 12

    fun enhanceIfNeeded(source: Bitmap): Bitmap {
        val meanLuma = estimateMeanLuma(source)
        val gain = gainForMeanLuma(meanLuma)
        if (gain <= 1.02f) return source

        val lift = shadowLiftForMeanLuma(meanLuma)
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (index in pixels.indices) {
            val color = pixels[index]
            val alpha = color ushr 24 and 0xFF
            val red = brighten(color ushr 16 and 0xFF, gain, lift)
            val green = brighten(color ushr 8 and 0xFF, gain, lift)
            val blue = brighten(color and 0xFF, gain, lift)
            pixels[index] = alpha shl 24 or (red shl 16) or (green shl 8) or blue
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    internal fun gainForMeanLuma(meanLuma: Float): Float {
        if (meanLuma >= START_ENHANCING_LUMA) return 1f
        if (meanLuma <= 1f) return MAX_GAIN
        return (START_ENHANCING_LUMA / meanLuma).coerceIn(1f, MAX_GAIN)
    }

    internal fun shadowLiftForMeanLuma(meanLuma: Float): Int =
        ((START_ENHANCING_LUMA - meanLuma).coerceAtLeast(0f) * 0.22f)
            .roundToInt()
            .coerceAtMost(18)

    private fun estimateMeanLuma(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return START_ENHANCING_LUMA

        var total = 0.0
        var count = 0
        var y = SAMPLE_STEP / 2
        while (y < height) {
            var x = SAMPLE_STEP / 2
            while (x < width) {
                val color = bitmap.getPixel(x, y)
                val red = color ushr 16 and 0xFF
                val green = color ushr 8 and 0xFF
                val blue = color and 0xFF
                total += red * 0.2126 + green * 0.7152 + blue * 0.0722
                count++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return if (count == 0) START_ENHANCING_LUMA else (total / count).toFloat()
    }

    private fun brighten(channel: Int, gain: Float, lift: Int): Int =
        (channel * gain + lift).roundToInt().coerceIn(0, 255)
}
