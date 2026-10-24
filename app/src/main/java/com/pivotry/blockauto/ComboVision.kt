package com.pivotry.blockauto

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pivotry.blockauto.core.ComboText
import com.pivotry.blockauto.core.Game
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device, bundled Latin-script OCR. COMBO is an animated, short-lived
 * caption; the large unlabelled high-score digits must never be mistaken for it.
 * Call scan() on the capture worker; deliver the result to that same worker.
 */
internal class ComboVision {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var working = false
    private var lastScanAt = Long.MIN_VALUE
    @Volatile private var closed = false

    fun scan(image: Game.Image, now: Long, boardTop: Float,
             cell: Float, onDetected: (Int) -> Unit) {
        if(closed || working || (lastScanAt!=Long.MIN_VALUE && now-lastScanAt<550)) return
        lastScanAt=now
        working=true
        // The floating combo banner appears above or near the top of the
        // board; excluding the bottom tray avoids unnecessary OCR workload.
        val bottom = if(boardTop>0f && cell>0f) boardTop+cell*3.2f
                     else image.height*.67f
        val height = min(image.height,max(64,bottom.toInt()))
        val bitmap = try {
            Bitmap.createBitmap(image.pixels.copyOf(image.width*height),
                image.width,height,Bitmap.Config.ARGB_8888)
        } catch(_ : Exception) {
            working=false
            return
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap,0))
                .addOnCompleteListener { task ->
                    working=false
                    bitmap.recycle()
                    if(!closed && task.isSuccessful) {
                        val value = readExplicitLabel(task.result)
                        if(value>0) onDetected(value)
                    }
                }
        } catch (_ : Exception) {
            working=false
            bitmap.recycle()
        }
    }
    fun close() {
        closed=true
        recognizer.close()
    }

    /** Require a real OCR COMBO token and a horizontally adjacent number. */
    private fun readExplicitLabel(text: Text): Int {
        val lines=text.textBlocks.flatMap { it.lines }
        var detected=-1
        for(line in lines) {
            val direct=ComboText.displayed(line.text)
            if(direct>0) {
                if(detected>0 && detected!=direct) return -1
                detected=direct
            }
        }
        if(detected>0) return detected
        for(label in lines) {
            if(!label.text.contains(Regex("(?i)\\bCOMBO\\b"))) continue
            val rect=label.boundingBox ?: continue
            for(number in lines) {
                if(number===label) continue
                val bounds=number.boundingBox ?: continue
                val candidate=ComboText.displayed("COMBO "+number.text.trim())
                if(candidate<1) continue
                // Avoid pairing the game-wide score with a banner.
                if(abs(bounds.centerY()-rect.centerY())>max(rect.height(),bounds.height())*.8f)continue
                if(bounds.left<rect.left+rect.width()*.40f)continue
                if(bounds.left>rect.right+rect.width()*2.0f)continue
                if(detected>0 && detected!=candidate) return -1
                detected=candidate
            }
        }
        return detected
    }
}
