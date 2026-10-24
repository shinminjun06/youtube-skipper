package com.pivotry.blockauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/** One live pointer; continued strokes keep ACTION_DOWN across observations. */
class AutoService : AccessibilityService() {
    companion object { @Volatile var live: AutoService? = null }
    private val main = Handler(Looper.getMainLooper())
    private var floating: LinearLayout? = null
    private var toggleButton: Button? = null
    private var guideView: View? = null
    private var guide: Guide? = null
    private data class Guide(val left: Float,val top: Float,val cell: Float,val cells: List<Pair<Int,Int>>,val width: Int,val height: Int)
    private var statusText: TextView? = null
    private lateinit var wm: WindowManager
    private var stroke: GestureDescription.StrokeDescription? = null
    private var fingerX = 0f
    private var fingerY = 0f
    private var inFlight = false
    private var cancelling = false
    private var cancelPending: (() -> Unit)? = null
    private var session = 0
    @Volatile var lastGestureError = ""; private set
    private val watchdog = Runnable { abortHold(); CaptureService.active?.halt("드래그 시간 초과") }

    override fun onServiceConnected() {
        super.onServiceConnected(); live = this
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
            text = "화면 공유 대기"; setTextColor(-1); textSize = 11f
            maxWidth = (resources.displayMetrics.widthPixels*.44f).toInt(); maxLines = 3
            isClickable = true // neutral owned touch target used only to cancel a gesture
        }
        layout.addView(button); layout.addView(status)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = (6*dp).toInt() }
        wm.addView(layout,params)
        val overlay = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val g = guide ?: return
                val origin = IntArray(2)
                getLocationOnScreen(origin)
                val sx = wm.maximumWindowMetrics.bounds.width().toFloat()/g.width
                val sy = wm.maximumWindowMetrics.bounds.height().toFloat()/g.height
                paint.style = Paint.Style.FILL
                paint.color = 0x8800FF99.toInt()
                for ((x,y) in g.cells) {
                    val l=(g.left+x*g.cell)*sx-origin[0]
                    val t=(g.top+y*g.cell)*sy-origin[1]
                    canvas.drawRoundRect(l+3,t+3,l+g.cell*sx-3,t+g.cell*sy-3,8f,8f,paint)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.color = 0xFF00FFAA.toInt()
                for ((x,y) in g.cells) {
                    val l=(g.left+x*g.cell)*sx-origin[0]
                    val t=(g.top+y*g.cell)*sy-origin[1]
                    canvas.drawRect(l+2,t+2,l+g.cell*sx-2,t+g.cell*sy-2,paint)
                }
            }
        }
        wm.addView(overlay,WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ))
        guideView = overlay
        floating = layout; toggleButton = button; statusText = status
    }
    fun showGuide(left: Float,top: Float,cell: Float,cells: List<Pair<Int,Int>>,width: Int,height: Int) {
        main.post { guide = Guide(left,top,cell,cells,width,height); guideView?.invalidate() }
    }
    fun clearGuide() { main.post { guide = null; guideView?.invalidate() } }
    fun foregroundPackage(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
    fun setRunning(value: Boolean) { main.post { toggleButton?.text = if (value) "■ 추천 정지" else "▶ 추천 시작" } }
    fun setStatus(value: String) { main.post { statusText?.text = value } }

    fun beginDrag(sx: Float,sy: Float,tx: Float,ty: Float,expectedApp: String?,done: (Boolean)->Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val actualApp = foregroundPackage()
        lastGestureError = ""
        if (stroke != null || inFlight || cancelling) { lastGestureError = "이전 터치 정리 중"; done(false); return }
        if (expectedApp != null && actualApp != null && actualApp != expectedApp) { lastGestureError = "다른 앱이 앞에 있음"; done(false); return }
        fingerX = sx; fingerY = sy
        session++
        main.removeCallbacks(watchdog); main.postDelayed(watchdog,9000)
        val pickup = GestureDescription.StrokeDescription(Path().apply { moveTo(sx,sy) },0,160,true)
        send(pickup,sx,sy) { ok ->
            if (ok) moveHeld(tx,ty,true,500,done) else done(false)
        }
    }
    fun moveHeld(tx: Float,ty: Float,keep: Boolean,duration: Long = 420,done: (Boolean)->Unit) {
        val old = stroke
        if (old == null || inFlight || cancelling) { done(false); return }
        val path = Path().apply { moveTo(fingerX,fingerY); lineTo(tx,ty) }
        send(old.continueStroke(path,0,duration,keep),tx,ty,done)
    }
    private fun send(next: GestureDescription.StrokeDescription,tx: Float,ty: Float,done: (Boolean)->Unit) {
        inFlight = true
        val once = AtomicBoolean(false)
        val epoch = session
        cancelPending = { if (once.compareAndSet(false,true)) done(false) }
        fun finish(ok: Boolean) {
            if (!once.compareAndSet(false,true) || epoch != session) return
            cancelPending = null; inFlight = false
            if (ok) { fingerX = tx; fingerY = ty; stroke = if (next.willContinue()) next else null }
            else stroke = null
            if (stroke == null) main.removeCallbacks(watchdog)
            done(ok)
        }
        val gesture = GestureDescription.Builder().addStroke(next).build()
        try {
            val accepted = dispatchGesture(gesture,object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { finish(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { lastGestureError = "Android가 터치를 취소함"; finish(false) }
            },main)
            android.util.Log.i("BlockBlastAuto","gesture accepted="+accepted+" continue="+next.willContinue()+" target="+tx+","+ty)
            if (!accepted) { lastGestureError = "접근성 입력이 거부됨"; finish(false) }
        } catch (e: Exception) { lastGestureError = e.javaClass.simpleName; finish(false) }
    }
    /** A fresh (non-continuing) gesture cancels the previous pointer with CANCEL,
     * including an in-flight terminating stroke. Its tap lands on our neutral
     * overlay, never on the game or another app. Do not wait for a pending UP. */
    fun abortHold() {
        main.removeCallbacks(watchdog)
        if (cancelling || (stroke == null && !inFlight)) return
        val target = statusText ?: return
        val location = IntArray(2); target.getLocationOnScreen(location)
        val x = location[0] + target.width*.5f
        val y = location[1] + target.height*.5f
        val pending = cancelPending
        session++; val epoch = session
        stroke = null; cancelPending = null; inFlight = true; cancelling = true
        pending?.invoke()
        fun finished() { if (session == epoch) { inFlight = false; cancelling = false } }
        try {
            val cancel = GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(Path().apply { moveTo(x,y) },0,1,false)
            ).build()
            val accepted = dispatchGesture(cancel,object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { finished() }
                override fun onCancelled(gestureDescription: GestureDescription?) { finished() }
            },main)
            if (!accepted) finished()
        } catch (_: Exception) { finished() }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) CaptureService.active?.checkForeground()
    }
    override fun onInterrupt() { CaptureService.active?.halt("접근성 서비스 중단"); abortHold() }
    override fun onDestroy() {
        abortHold()
        CaptureService.active?.halt("접근성 서비스 종료")
        if (live === this) live = null
        val overlay = floating; floating = null
        val guideOverlay = guideView; guideView = null
        main.postDelayed({ overlay?.let { runCatching { wm.removeView(it) } }; guideOverlay?.let { runCatching { wm.removeView(it) } }; main.removeCallbacksAndMessages(null) },200)
        super.onDestroy()
    }
}
