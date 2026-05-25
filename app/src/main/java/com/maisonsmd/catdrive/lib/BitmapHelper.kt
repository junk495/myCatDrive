package com.maisonsmd.catdrive.lib

import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.util.Base64
import android.util.Size
import androidx.core.graphics.scale
import androidx.core.graphics.toColor
import timber.log.Timber
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.math.sqrt

class BitmapHelper {

    // Draw bitmap without antialiasing
    class AliasingDrawableWrapper(wrapped: Drawable?) : DrawableWrapper(wrapped) {
        override fun draw(canvas: Canvas) {
            val oldDrawFilter = canvas.drawFilter
            canvas.drawFilter = DRAW_FILTER
            super.draw(canvas)
            canvas.drawFilter = oldDrawFilter
        }

        companion object {
            private val DRAW_FILTER: DrawFilter = PaintFlagsDrawFilter(Paint.FILTER_BITMAP_FLAG, 0)
        }
    }

    fun toBase64(source: Bitmap): String {
        return Base64.encodeToString(toBlackAndWhiteBuffer(source), Base64.NO_WRAP)
    }

    fun toBlackAndWhiteBuffer(source: Bitmap): ByteArray {
        val w = source.width
        val h = source.height
        val byteWidth = (w + 7) / 8
        val buffer = ByteArray(byteWidth * h) { _ -> 0 }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = if (source.getPixel(x, y) == Color.BLACK) 1 else 0
                val byteIndex = y * byteWidth + x / 8
                buffer[byteIndex] = buffer[byteIndex] or (px shl (7 - x % 8)).toByte()
            }
        }
        return buffer
    }

    private fun ditherImage(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        paint.isAntiAlias = false

        val newFgColor = Color.BLACK
        val newBgColor = Color.WHITE

        fun isValidPixel(_x: Int, _y: Int): Boolean {
            if (_x < 0 || _x >= out.width)
                return false
            if (_y < 0 || _y >= out.height)
                return false
            return true
        }

        val palette = getPalette(source)
        // Timber.d(palette.toString())

        if (palette.size < 2) {
            Timber.e("Unable to dither with this color palette size, min is 2")
            return source
        }

        canvas.drawColor(newBgColor)
        paint.color = newFgColor

        for (x in 0 until out.width) {
            for (y in 0 until out.height) {
                val pixel = roundColor(source.getPixel(x, y).toColor())
                // Foreground pixels
                if (pixel == palette.last()) {
                    canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
                }
                // Background color
                else if (pixel == palette.first()) {

                }
                // Gray pixels
                else {
                    var hasGreyNeighbor = false
                    var nFgNeighbors = 0
                    for (i in -1..1)
                        for (j in -1..1) {
                            if (!isValidPixel(x + i, y + j))
                                continue
                            val c = roundColor(source.getPixel(x + i, y + j).toColor())
                            if (c != palette.first() && c != palette.last())
                                hasGreyNeighbor = true
                            // This pixel is dark gray and near FG pixel
                            if (palette.indexOf(pixel) <= palette.size / 2)
                                if (c == palette.last())
                                    nFgNeighbors++
                        }

                    // Fill grey pixels when some pixels around are fg (to avoid missing pixels at corners)
                    if (nFgNeighbors >= 2)
                        canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
                    // only dither when multiple grey pixels are found as a group
                    if (x % 3 == 0 && y % 2 == 0 && hasGreyNeighbor) {
                        canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
                    }
                }
            }
        }

        return out
    }

    fun compressBitmap(source: Bitmap?, size: Size): Bitmap {
        if (source == null)
            return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)

        val scaledSource = source.scale(size.width, size.height, false)
        return ditherImage(scaledSource)
    }

    /**
     * Calculate color similarity, result (0.0 -> 1.0)
     */
    fun colorSimilarity(c1: Color, c2: Color): Double {
        val r: Double = (c1.red() - c2.red()).toDouble()
        val g: Double = (c1.green() - c2.green()).toDouble()
        val b: Double = (c1.blue() - c2.blue()).toDouble()
        val a: Double = (c1.alpha() - c2.alpha()).toDouble()
        val distance = sqrt(r * r + g * g + b * b + a * a) / sqrt(4.0)
        // Timber.d("similarity: $c1 - $c2: $distance")
        return 1.0 - distance
    }

    private fun colorAvg(c: Color): Double {
        val r: Double = (c.red()).toDouble()
        val g: Double = (c.green()).toDouble()
        val b: Double = (c.blue()).toDouble()
        val a: Double = (c.alpha()).toDouble()
        return sqrt(r * r + g * g + b * b + a * a) / sqrt(4.0)
    }

    private fun roundColor(c: Color): Color {
        // Limit number of colors in palette to 4 ranges
        fun roundTo(value: Float): Float {
            if (value < 0.25f) return 0f
            if (value < 0.5f) return 0.4f
            if (value < 0.75f) return 0.7f
            return 1f
        }

        val result = Color.valueOf(
            roundTo(c.red()),
            roundTo(c.green()),
            roundTo(c.blue()),
            roundTo(c.alpha())
        )

        // Must be background of Google map turn icons
        if (result.alpha() < 0.1f)
            return Color.BLACK.toColor()

        return result
    }

    private fun getPalette(bitmap: Bitmap): List<Color> {
        val result = mutableListOf<Color>()
        for (x in 0 until bitmap.width)
            for (y in 0 until bitmap.height) {
                val c = roundColor(bitmap.getPixel(x, y).toColor())
                if (c != Color.TRANSPARENT.toColor())
                    result.add(c)
            }
        return result.distinct().sortedBy { colorAvg(it) }
    }
}