package com.pivotry.blockauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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
    private var statusText: TextView? = null
    private lateinit var wm: WindowManager
    private var stroke: GestureDescription.StrokeDescription? = null
    private var fingerX = 0f
    private var fingerY = 0f
    private var sourceX = 0f
    private var sourceY = 0f
    private var inFlight = false
    private var abortRequested = false
    private var session = 0
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
            text = "▶ 자동 시작"
            setOnClickListener {
                val capture = CaptureService.active
                if (capture == null) setStatus("앱에서 화면 공유를 시작하세요") else capture.toggle()
            }
        }
        val status = TextView(this).apply {
            text = "화면 공유 대기"; setTextColor(-1); textSize = 11f
            maxWidth = (resources.displayMetrics.widthPixels*.44f).toInt(); maxLines = 3
        }
        layout.addView(button); layout.addView(status)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = (6*dp).toInt() }
        wm.addView(layout,params)
        floating = layout; toggleButton = button; statusText = status
    }
    fun foregroundPackage(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
    fun setRunning(value: Boolean) { main.post { toggleButton?.text = if (value) "■ 자동 정지" else "▶ 자동 시작" } }
    fun setStatus(value: String) { main.post { statusText?.text = value } }

    fun beginDrag(sx: Float,sy: Float,tx: Float,ty: Float,expectedApp: String,done: (Boolean)->Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (stroke != null || inFlight || foregroundPackage() != expectedApp) { done(false); return }
        sourceX = sx; sourceY = sy; fingerX = sx; fingerY = sy
        abortRequested = false; session++
        main.removeCallbacks(watchdog); main.postDelayed(watchdog,9000)
        val pickup = GestureDescription.StrokeDescription(Path().apply { moveTo(sx,sy) },0,160,true)
        send(pickup,sx,sy) { ok ->
            if (ok) moveHeld(tx,ty,true,500,done) else done(false)
        }
    }
    fun moveHeld(tx: Float,ty: Float,keep: Boolean,duration: Long = 420,done: (Boolean)->Unit) {
        val old = stroke
        if (old == null || inFlight || abortRequested) { done(false); return }
        val path = Path().apply { moveTo(fingerX,fingerY); lineTo(tx,ty) }
        send(old.continueStroke(path,0,duration,keep),tx,ty,done)
    }
    private fun send(next: GestureDescription.StrokeDescription,tx: Float,ty: Float,done: (Boolean)->Unit) {
        inFlight = true
        val once = AtomicBoolean(false)
        val epoch = session
        fun finish(ok: Boolean) {
            if (!once.compareAndSet(false,true) || epoch != session) return
            inFlight = false
            if (ok) { fingerX = tx; fingerY = ty; stroke = if (next.willContinue()) next else null }
            else stroke = null
            if (stroke == null) main.removeCallbacks(watchdog)
            if (abortRequested && stroke != null) { returnToTray(); done(false) }
            else done(ok && !abortRequested)
        }
        val gesture = GestureDescription.Builder().addStroke(next).build()
        try {
            val accepted = dispatchGesture(gesture,object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { finish(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { finish(false) }
            },main)
            if (!accepted) finish(false)
        } catch (_: Exception) { finish(false) }
    }
    /** Stop during a gesture waits for its callback, then releases back at the source. */
    fun abortHold() {
        abortRequested = true
        if (!inFlight) returnToTray()
    }
    private fun returnToTray() {
        val old = stroke ?: return
        val path = Path().apply { moveTo(fingerX,fingerY); lineTo(sourceX,sourceY) }
        abortRequested = false
        send(old.continueStroke(path,0,220,false),sourceX,sourceY) { }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) CaptureService.active?.checkForeground()
    }
    override fun onInterrupt() { CaptureService.active?.halt("접근성 서비스 중단"); abortHold() }
    override fun onDestroy() {
        CaptureService.active?.halt("접근성 서비스 종료")
        main.removeCallbacksAndMessages(null)
        if (live === this) live = null
        floating?.let { runCatching { wm.removeView(it) } }; floating = null
        super.onDestroy()
    }
}
