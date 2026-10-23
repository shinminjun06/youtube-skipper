package com.pivotry.blockauto

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.view.*
import com.pivotry.blockauto.core.*
import kotlin.math.*

/** Controlled touch/render fixture. Not the Block Blast game, never a launcher. */
class TouchFixtureActivity : Activity() {
    companion object { @Volatile var live: TouchFixtureActivity? = null }
    @Volatile var placed = 0
    @Volatile var cancelled = 0
    @Volatile var held = false
    @Volatile var downs = 0
    @Volatile var ups = 0
    private var board = 0L
    private val pieces = arrayOf(Game.Piece(0,0,1,0,2,0),Game.Piece(0,0,1,0,1,1),Game.Piece(1,0,0,1,1,1))
    private val available = booleanArrayOf(true,true,true)
    private var selected = -1
    private var grabX = 0f
    private var grabY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private lateinit var game: View
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); live = this
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        window.setDecorFitsSystemWindows(false)
        game = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val left get() = width*.058f
            val top get() = height*.2357f
            val cell get() = width*.8858f/8
            val lift get() = cell*1.85f // deliberately differs from the old +115px constant
            fun rect(l: Float,t: Float,w: Float,h: Float,c: Int) { paint.color=c; canvasRef!!.drawRect(l,t,l+w,t+h,paint) }
            var canvasRef: Canvas? = null
            fun tile(x: Float,y: Float,size: Float) {
                rect(x+1,y+1,size-2,size-2,Color.rgb(251,220,78))
                rect(x+2,y+2,size-4,size*.12f,Color.rgb(255,237,135))
                rect(x+size*.86f,y+size*.12f,size*.1f,size*.85f,Color.rgb(215,165,39))
            }
            fun trayX(slot: Int,piece: Game.Piece) = width*(.21f+.29f*slot)-piece.w*cell*.447f/2
            fun trayY(piece: Game.Piece) = top+8*cell+cell*2.4f-piece.h*cell*.447f/2
            override fun onDraw(canvas: Canvas) {
                canvasRef=canvas; canvas.drawColor(Color.rgb(187,133,41))
                rect(left-2,top-2,8*cell+4,8*cell+4,Color.rgb(62,41,9))
                for(y in 0..7)for(x in 0..7) {
                    rect(left+x*cell+2,top+y*cell+2,cell-4,cell-4,Color.rgb(86,61,26))
                    if(board and (1L shl (8*y+x)) != 0L)tile(left+x*cell,top+y*cell,cell)
                }
                for(slot in 0..2)if(available[slot]&&slot!=selected) {
                    val piece=pieces[slot]; val size=cell*.447f
                    for(i in piece.xs.indices)tile(trayX(slot,piece)+piece.xs[i]*size,trayY(piece)+piece.ys[i]*size,size)
                }
                if(selected>=0) {
                    val p=pieces[selected]
                    val ox=fingerX-grabX*cell;val oy=fingerY-lift-grabY*cell
                    for(i in p.xs.indices)tile(ox+p.xs[i]*cell,oy+p.ys[i]*cell,cell)
                }
                canvasRef=null
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downs++;fingerX=event.x;fingerY=event.y
                        for(slot in 0..2)if(available[slot]) {
                            val p=pieces[slot];val unit=cell*.447f
                            val rx=(event.x-trayX(slot,p))/unit;val ry=(event.y-trayY(p))/unit
                            if(p.xs.indices.any { floor(rx).toInt()==p.xs[it]&&floor(ry).toInt()==p.ys[it] }) {
                                selected=slot;grabX=rx;grabY=ry;held=true;break
                            }
                        }
                    }
                    MotionEvent.ACTION_MOVE -> { fingerX=event.x;fingerY=event.y }
                    MotionEvent.ACTION_UP -> {
                        ups++;fingerX=event.x;fingerY=event.y
                        if(selected>=0) {
                            val ox=fingerX-grabX*cell;val oy=fingerY-lift-grabY*cell
                            val x=((ox-left)/cell).roundToInt();val y=((oy-top)/cell).roundToInt()
                            val result=Rules.place(board,pieces[selected],x,y)
                            if(result!=null&&abs(ox-left-x*cell)<cell*.2&&abs(oy-top-y*cell)<cell*.2) {
                                board=result.board;available[selected]=false;placed++
                                if(available.none { it })available.fill(true)
                            } else cancelled++
                        }
                        selected=-1;held=false
                    }
                    MotionEvent.ACTION_CANCEL -> { selected=-1;held=false;cancelled++ }
                }
                invalidate();return true
            }
        }
        setContentView(game)
    }
    override fun onDestroy() { if(live===this)live=null;super.onDestroy() }
}
