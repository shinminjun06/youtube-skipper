package com.pivotry.blockauto

import android.media.Image
import com.pivotry.blockauto.core.Game
import com.pivotry.blockauto.core.PixelReader

/** Copy/downsample once. Never retain a closed Image, hardware buffer or Bitmap. */
object Vision {
    fun copy(image: Image): Game.Image {
        val plane = image.planes[0]
        return PixelReader.read(plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride,720)
    }
}
