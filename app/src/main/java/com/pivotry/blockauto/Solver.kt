package com.pivotry.blockauto

object Solver {
 data class Piece(val cells:List<Pair<Int,Int>>) {
  val w = cells.maxOf { it.first }+1
  val h = cells.maxOf { it.second }+1
 }
 data class Move(val piece:Int,val x:Int,val y:Int)
 data class Result(val moves:List<Move>,val score:Double)
 fun parse(raw:String):Piece {
  val rows=raw.trim().split("/")
  val cells=rows.flatMapIndexed { y,row -> row.mapIndexedNotNull { x,c -> if(c=='#') x to y else null } }
  require(cells.isNotEmpty() && rows.size<=8 && rows.all { it.length<=8 && it.all { c -> c=='#' || c=='.' } })
  return Piece(cells)
 }
 private fun place(b:Long,p:Piece,x:Int,y:Int):Pair<Long,Int>? {
  var n=b
  for ((dx,dy) in p.cells) {
   val bit=1L shl ((y+dy)*8+x+dx)
   if(n and bit != 0L) return null
   n=n or bit
  }
  var mask=0L; var lines=0
  for (row in 0..7) {
   val m=255L shl (row*8)
   if(n and m == m) { mask=mask or m; lines++ }
  }
  for (col in 0..7) {
   var m=0L
   for(row in 0..7) m=m or (1L shl (row*8+col))
   if(n and m == m) { mask=mask or m; lines++ }
  }
  return (n and mask.inv()) to lines
 }
 private fun value(b:Long):Double {
  var squares=0
  for (size in 2..4) for(y in 0..8-size) for(x in 0..8-size) {
   var clear=true
   for(dy in 0 until size) for(dx in 0 until size)
    if(b and (1L shl ((y+dy)*8+x+dx)) != 0L) clear=false
   if(clear) squares+=size*size
  }
  return (64-java.lang.Long.bitCount(b)).toDouble()+squares*.16
 }
 fun best(board:Long,pieces:List<Piece>):Result? {
  require(pieces.size==3)
  var best:Result?=null
  fun dfs(b:Long,used:Int,moves:List<Move>,score:Double) {
   if(used==7) {
    val result=Result(moves,score+value(b))
    if(best==null || result.score>best!!.score) best=result
    return
   }
   for(i in 0..2) if(used and (1 shl i)==0) {
    val p=pieces[i]
    for(y in 0..8-p.h) for(x in 0..8-p.w) {
     val next=place(b,p,x,y) ?: continue
     dfs(next.first,used or (1 shl i),moves+Move(i,x,y),score+18.0*next.second)
    }
   }
  }
  dfs(board,0,emptyList(),0.0)
  return best
 }
}