package com.pivotry.blockauto

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.pivotry.blockauto.core.Game
import com.pivotry.blockauto.core.GuideMask
import kotlin.math.abs

/**
 * Read-only recommendation overlay. No dispatchGesture, held pointers,
 * simulated taps or direct gameplay input are present in this service.
 */
class AutoService : AccessibilityService() {
    companion object { @Volatile var live: AutoService? = null }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var floating: LinearLayout? = null
    private var toggleButton: Button? = null
    private var guideView: View? = null
    private var statusText: TextView? = null
    private var guideClear: Runnable? = null
    private data class Guide(
        val left: Float, val top: Float, val cell: Float,
        val cells: List<Pair<Int,Int>>, val width: Int, val height: Int
    )
    @Volatile private var guide: Guide? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDB202831.toInt())
            setPadding((8*dp).toInt(),(3*dp).toInt(),(8*dp).toInt(),(5*dp).toInt())
        }
        val button = Button(this).apply {
            text = "▶ 추천 시작"
            setOnClickListener {
                val capture = CaptureService.active
                if (capture == null) setStatus("앱에서 화면 공유를 시작하세요") else capture.toggle()
            }
        }
        val status = TextView(this).apply {
            text = "화면 공유 대기"
            setTextColor(-1)
            textSize = 11f
            maxWidth = (resources.displayMetrics.widthPixels*.44f).toInt()
            maxLines = 3
        }
        layout.addView(button); layout.addView(status)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels*.53f).toInt()
            y = (6*dp).toInt()
        }
        fun attachDrag(target: View,clickOnRelease: Boolean) {
            var downX=0f; var downY=0f; var startX=0; var startY=0
            var moved=false
            val slop=android.view.ViewConfiguration.get(this).scaledTouchSlop
            target.setOnTouchListener { _,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX=event.rawX;downY=event.rawY
                        startX=params.x;startY=params.y;moved=false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx=event.rawX-downX;val dy=event.rawY-downY
                        if(abs(dx)>slop||abs(dy)>slop)moved=true
                        if(moved) {
                            val bounds=wm.maximumWindowMetrics.bounds
                            params.x=(startX+dx.toInt()).coerceIn(0,(bounds.width()-layout.width).coerceAtLeast(0))
                            params.y=(startY+dy.toInt()).coerceIn(0,(bounds.height()-layout.height).coerceAtLeast(0))
                            runCatching { wm.updateViewLayout(layout,params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if(!moved&&clickOnRelease)target.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        attachDrag(button,true)
        attachDrag(status,false)
        wm.addView(layout,params)
        val overlay=object : View(this) {
            private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val g=guide ?: return
                val origin=IntArray(2)
                getLocationOnScreen(origin)
                val sx=wm.maximumWindowMetrics.bounds.width().toFloat()/g.width
                val sy=wm.maximumWindowMetrics.bounds.height().toFloat()/g.height
                paint.style=Paint.Style.STROKE
                paint.strokeWidth=2.5f*resources.displayMetrics.density
                paint.color=0xFF00FFAA.toInt()
                for((x,y) in g.cells) {
                    val l=(g.left+x*g.cell)*sx-origin[0]
                    val t=(g.top+y*g.cell)*sy-origin[1]
                    val w=g.cell*sx;val h=g.cell*sy
                    canvas.drawRoundRect(l+w*.12f,t+h*.12f,
                        l+w*.88f,t+h*.88f,5f,5f,paint)
                }
            }
        }
        wm.addView(overlay,WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ))
        floating=layout;toggleButton=button;guideView=overlay;statusText=status
    }

    /** Strip our own guide ink before analyzing the board; overlay stays visible. */
    fun cleanCaptured(image: Game.Image): Game.Image {
        val g=guide ?: return image
        val coords=IntArray(g.cells.size*2)
        g.cells.forEachIndexed { i,p -> coords[2*i]=p.first;coords[2*i+1]=p.second }
        return GuideMask.strip(image,g.left,g.top,g.cell,g.width,g.height,coords)
    }
    fun showGuide(left: Float,top: Float,cell: Float,cells: List<Pair<Int,Int>>,width: Int,height: Int) {
        main.post {
            guideClear?.let { main.removeCallbacks(it) }; guideClear=null
            val next=Guide(left,top,cell,cells,width,height)
            if(guide!=next) { guide=next;guideView?.invalidate() }
        }
    }
    fun clearGuide(immediate: Boolean=false) {
        main.post {
            guideClear?.let { main.removeCallbacks(it) }
            val clear=Runnable { guide=null;guideView?.invalidate();guideClear=null }
            if(immediate)clear.run() else {
                guideClear=clear
                main.postDelayed(clear,900)
            }
        }
    }
    fun foregroundPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (_:Exception) { null }
    fun setRunning(value: Boolean) {
        main.post { toggleButton?.text=if(value)"■ 추천 정지" else "▶ 추천 시작" }
    }
    fun setStatus(value: String) { main.post { statusText?.text=value } }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(event?.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            CaptureService.active?.checkForeground()
    }
    override fun onInterrupt() { CaptureService.active?.halt("접근성 서비스 중단") }
    override fun onDestroy() {
        CaptureService.active?.halt("접근성 서비스 종료")
        if(live===this)live=null
        guideClear?.let { main.removeCallbacks(it) }
        guide=null
        floating?.let { runCatching { wm.removeView(it) } }
        guideView?.let { runCatching { wm.removeView(it) } }
        floating=null;guideView=null
        super.onDestroy()
    }
}
