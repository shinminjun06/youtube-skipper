package com.pivotry.blockauto

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/** Geometry and colors calibrated against the supplied 709 x 1536 screenshot.
 * Supports proportional portrait captures. Does not use any network API. */
object Vision {
 data class Shape(val slot:Int,val piece:Solver.Piece,val sourceX:Float,val sourceY:Float)
 data class Frame(val board:Long,val shapes:List<Shape>,val left:Float,val top:Float,val cell:Float,val width:Int,val height:Int)
 private fun gold(c:Int):Boolean {
  val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
  return r>205 && g>160 && b<175 && r>g+12 && g>b+35
 }
 fun read(b:Bitmap):Frame? {
  val w=b.width;val h=b.height
  if(w<400 || h<750) return null
  val left=w*41f/709f;val top=h*362f/1536f
  val cell=(w*628f/709f)/8f
  // Reject apps other than a board having the expected dark tile background.
  val check=b.getPixel((left+cell*.5f).toInt().coerceIn(0,w-1),(top+cell*.5f).toInt().coerceIn(0,h-1))
  if(Color.red(check)>165 || Color.green(check)>155) return null
  var board=0L
  for(y in 0..7) for(x in 0..7) {
   var hits=0
   for(dy in -1..1) for(dx in -1..1) {
    val sx=(left+(x+.5f)*cell+dx*cell*.11f).toInt().coerceIn(0,w-1)
    val sy=(top+(y+.5f)*cell+dy*cell*.11f).toInt().coerceIn(0,h-1)
    if(gold(b.getPixel(sx,sy))) hits++
   }
   if(hits>=4) board=board or (1L shl (y*8+x))
  }
  val shapes=ArrayList<Shape>()
  val unit=cell*0.447f
  val ranges=listOf(.085f to .32f,.40f to .60f,.69f to .88f)
  for((slot,range) in ranges.withIndex()) {
   val x1=(w*range.first).toInt();val x2=(w*range.second).toInt()
   val y1=(h*.715f).toInt();val y2=(h*.812f).toInt()
   var minX=w;var minY=h;var maxX=0;var maxY=0
   for(y in y1 until y2 step 2) for(x in x1 until x2 step 2) {
    if(gold(b.getPixel(x,y))) {
     if(x<minX)minX=x
     if(x>maxX)maxX=x
     if(y<minY)minY=y
     if(y>maxY)maxY=y
    }
   }
   if(maxX<minX || maxY<minY) continue
   val cols=((maxX-minX)/unit).roundToInt()+1
   val rows=((maxY-minY)/unit).roundToInt()+1
   if(cols !in 1..5 || rows !in 1..5) continue
   val cells=ArrayList<Pair<Int,Int>>()
   for(cy in 0 until rows) for(cx in 0 until cols) {
    val px=(minX+(cx+.5f)*unit).toInt().coerceIn(0,w-1)
    val py=(minY+(cy+.5f)*unit).toInt().coerceIn(0,h-1)
    var hits=0
    for(dy in -2..2 step 2) for(dx in -2..2 step 2)
     if(gold(b.getPixel((px+dx).coerceIn(0,w-1),(py+dy).coerceIn(0,h-1))))hits++
    if(hits>=3) cells.add(cx to cy)
   }
   if(cells.isEmpty() || cells.size>9)continue
   // Gold features must form whole tiles, not decorative UI fragments.
   val piece=Solver.Piece(cells)
   shapes.add(Shape(slot,piece,(minX+maxX)/2f,(minY+maxY)/2f))
  }
  return Frame(board,shapes,left,top,cell,w,h)
 }
}
