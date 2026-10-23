package com.pivotry.blockauto

import android.graphics.*
import android.media.Image
import com.pivotry.blockauto.core.Game
import kotlin.math.min
import kotlin.math.roundToInt

/** Reuses bitmap buffers; closes the Image before vision/planning. */
class Vision : AutoCloseable {
    private var padded: Bitmap? = null
    private var scaled: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    fun copy(image: Image): Game.Image {
        val plane = image.planes[0]
        require(plane.pixelStride == 4) { "Unsupported pixel layout" }
        val strideWidth = plane.rowStride / plane.pixelStride
        var full = padded
        if (full == null || full.width != strideWidth || full.height != image.height) {
            full?.recycle()
            full = Bitmap.createBitmap(strideWidth, image.height, Bitmap.Config.ARGB_8888)
            padded = full
        }
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        val width = min(720, image.width)
        val height = (image.height * width.toFloat() / image.width).roundToInt()
        var small = scaled
        if (small == null || small.width != width || small.height != height) {
            small?.recycle()
            small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            scaled = small
        }
        Canvas(small).drawBitmap(full, Rect(0,0,image.width,image.height), Rect(0,0,width,height), paint)
        val pixels = IntArray(width * height)
        small.getPixels(pixels,0,width,0,0,width,height)
        return Game.Image(width,height,pixels)
    }
    override fun close() {
        padded?.recycle(); padded = null
        scaled?.recycle(); scaled = null
    }
}
