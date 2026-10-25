package com.pivotry.blockauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.MotionEvent
import kotlin.math.abs
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
    private var floatingParams: WindowManager.LayoutParams? = null
    private var autoDock = false
    private var toggleButton: Button? = null
    private var modeButton: Button? = null
    private var guideView: View? = null
    private var guide: Guide? = null
    private var guideClear: Runnable? = null
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
            text = "▶ 시작"
            setOnClickListener {
                val capture = CaptureService.active
                if (capture == null) setStatus("앱에서 화면 공유를 시작하세요") else capture.toggle()
            }
        }
        val status = TextView(this).apply {
            text = "화면 공유 대기"; setTextColor(-1); textSize = 11f
            maxWidth = (resources.displayMetrics.widthPixels*.44f).toInt(); maxLines = 3
            isClickable = true // drag handle and neutral target for cancelling a gesture
        }
        val mode = Button(this).apply {
            text = "모드: 추천"
            setOnClickListener {
                val capture=CaptureService.active
                if(capture==null) {
                    val current=getSharedPreferences("play_mode",MODE_PRIVATE).getBoolean("auto",false)
                    val next=!current
                    getSharedPreferences("play_mode",MODE_PRIVATE).edit().putBoolean("auto",next).apply()
                    setAutomatic(next)
                    setStatus("모드 변경됨 · 앱에서 화면 공유를 시작하세요")
                } else capture.setAutomatic(!capture.isAutomatic())
            }
        }
        layout.addView(button); layout.addView(mode); layout.addView(status)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = (6*dp).toInt() }
        // Drag the floating controls anywhere on the display. A short tap on
        // the button still toggles recommendations; moving does not click it.
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (resources.displayMetrics.widthPixels*.53f).toInt()
        params.y = (6*dp).toInt()
        fun attachDrag(target: View,clickOnRelease: Boolean) {
            var downX=0f; var downY=0f; var startX=0; var startY=0
            var moved=false
            val slop=android.view.ViewConfiguration.get(this).scaledTouchSlop
            target.setOnTouchListener { _,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX=event.rawX; downY=event.rawY
                        startX=params.x; startY=params.y; moved=false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // While auto input is running the dock must remain
                        // outside the board/lift path. The stop tap still works.
                        if(autoDock)
                            return@setOnTouchListener true
                        val dx=event.rawX-downX; val dy=event.rawY-downY
                        if(abs(dx)>slop || abs(dy)>slop) moved=true
                        if(moved) {
                            val bounds=wm.maximumWindowMetrics.bounds
                            params.x=(startX+dx.toInt()).coerceIn(0,(bounds.width()-layout.width).coerceAtLeast(0))
                            params.y=(startY+dy.toInt()).coerceIn(0,(bounds.height()-layout.height).coerceAtLeast(0))
                            wm.updateViewLayout(layout,params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if(!moved && clickOnRelease) target.performClick()
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
        floatingParams=params
        // Accessibility overlays also appear in MediaProjection screenshots.
        // The v1.3.3 field failure had 3/4 yellow T cell centers hidden by
        // our controls at y=69..294. Keep the lift path clear in auto mode.
        layout.post { dockControls(autoDock) }
        val overlay = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val g = guide ?: return
                val origin = IntArray(2)
                getLocationOnScreen(origin)
                val sx = wm.maximumWindowMetrics.bounds.width().toFloat()/g.width
                val sy = wm.maximumWindowMetrics.bounds.height().toFloat()/g.height
                // MediaProjection also captures our accessibility overlay.
                // A filled highlight corrupts the 3x3 interior color probes
                // (0.3..0.7 of each cell) and stalls recognition. Draw ONLY
                // an inset perimeter in the outer 12% of each cell. It stays
                // outside both the cell interior probes and the grid lines.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (2.5f*resources.displayMetrics.density)
                paint.color = 0xFF00FFAA.toInt()
                for ((x,y) in g.cells) {
                    val l=(g.left+x*g.cell)*sx-origin[0]
                    val t=(g.top+y*g.cell)*sy-origin[1]
                    val w=g.cell*sx; val h=g.cell*sy
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
        guideView = overlay
        floating = layout; toggleButton = button; modeButton=mode; statusText = status
        setAutomatic(CaptureService.active?.isAutomatic() ?: getSharedPreferences("play_mode",MODE_PRIVATE).getBoolean("auto",false))
    }
    /** In recommendation mode the overlay is never itself treated as a tile. */
    fun cleanCaptured(image: com.pivotry.blockauto.core.Game.Image): com.pivotry.blockauto.core.Game.Image {
        val g=guide ?: return image
        val coords=IntArray(g.cells.size*2)
        g.cells.forEachIndexed { i,p -> coords[2*i]=p.first;coords[2*i+1]=p.second }
        return com.pivotry.blockauto.core.GuideMask.strip(
            image,g.left,g.top,g.cell,g.width,g.height,coords)
    }
    fun showGuide(left: Float,top: Float,cell: Float,cells: List<Pair<Int,Int>>,width: Int,height: Int) {
        main.post {
            guideClear?.let { main.removeCallbacks(it) }; guideClear=null
            val next=Guide(left,top,cell,cells,width,height)
            if(guide!=next) { guide=next; guideView?.invalidate() }
        }
    }
    fun clearGuide(immediate: Boolean = false) {
        main.post {
            guideClear?.let { main.removeCallbacks(it) }
            val clear=Runnable { guide=null; guideView?.invalidate(); guideClear=null }
            if(immediate) clear.run() else {
                guideClear=clear
                main.postDelayed(clear,900)
            }
        }
    }
    fun foregroundPackage(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
    /**
     * Accessibility overlays appear INSIDE MediaProjection. A fixed panel,
     * whether top or bottom docked, can cover a tall held piece. On the
     * uploaded v1.3.5 failure a blue vertical 5-cell piece lost its bottom
     * two cell centers under the bottom-right panel during PICKED.
     *
     * Keep all foreground controls available before starting, but remove
     * them from BOTH the screen and captured pixels for the ENTIRE automatic
     * session. The foreground notification's "종료" action remains available
     * as an emergency stop. The panel reappears after halt/verification.
     */
    private fun syncControlsVisibility(running: Boolean) {
        val hide = autoDock && running
        floating?.visibility=if(hide) View.INVISIBLE else View.VISIBLE
        guideView?.visibility=if(hide) View.INVISIBLE else View.VISIBLE
        android.util.Log.i("BlockBlastAuto",
            "capture-safe controls: "+(if(hide)"HIDDEN" else "VISIBLE"))
    }
    fun setRunning(value: Boolean) { main.post {
        toggleButton?.text=if (value) "■ 중지" else "▶ 시작"
        modeButton?.isEnabled=!value
        syncControlsVisibility(value)
        if(!value) floating?.post {dockControls(autoDock)}
    } }
    /** Automatic play docks controls below the tray, never above the board. */
    private fun dockControls(automatic: Boolean) {
        val view=floating ?: return
        val params=floatingParams ?: return
        if(!::wm.isInitialized || !view.isAttachedToWindow)return
        val bounds=wm.maximumWindowMetrics.bounds
        val margin=(6*resources.displayMetrics.density).toInt()
        // Gravity.BOTTOM anchors against the actual available window area.
        // Computing top-based y from maximumWindowMetrics pushed part of the
        // controls under gesture-nav bars on some fullscreen Android phones.
        params.gravity=if(automatic) Gravity.BOTTOM or Gravity.END else Gravity.TOP or Gravity.START
        params.x=if(automatic)margin else (bounds.width()*.53f).toInt()
        params.y=margin
        runCatching { wm.updateViewLayout(view,params) }
        android.util.Log.i("BlockBlastAuto","controls docked "+(if(automatic)"BOTTOM" else "TOP")+
            " margin="+margin+" bounds="+bounds.width()+"x"+bounds.height())
    }
    fun setAutomatic(value: Boolean) { main.post {
        autoDock=value
        modeButton?.text=if (value) "모드: 자동" else "모드: 추천"
        dockControls(value)
        syncControlsVisibility(CaptureService.active?.isRunning()==true)
    } }
    fun setStatus(value: String) { main.post {
        statusText?.text=value
        // A status change may increase panel height. Keep its bottom edge
        // anchored BELOW the playing area even after layout has remeasured.
        if(autoDock) floating?.post {dockControls(true)}
    } }

    fun beginDrag(sx: Float,sy: Float,tx: Float,ty: Float,expectedApp: String?,done: (Boolean)->Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val actualApp = foregroundPackage()
        lastGestureError = ""
        if (CaptureService.active?.isAutomatic()!=true) { lastGestureError="추천 모드에서는 자동 터치 금지"; done(false); return }
        // Enforce clear screenshots before any held pointer is created,
        // even if the main-thread running-state callback was delayed.
        syncControlsVisibility(true)
        if (stroke != null || inFlight || cancelling) { lastGestureError = "이전 터치 정리 중"; done(false); return }
        if (expectedApp != null && actualApp != null && actualApp != expectedApp) { lastGestureError = "다른 앱이 앞에 있음"; done(false); return }
        fingerX = sx; fingerY = sy
        session++
        main.removeCallbacks(watchdog); main.postDelayed(watchdog,14500)
        val pickup = GestureDescription.StrokeDescription(Path().apply { moveTo(sx,sy) },0,160,true)
        send(pickup,sx,sy) { ok ->
            if (ok) moveHeld(tx,ty,true,500,done) else done(false)
        }
    }
    fun moveHeld(tx: Float,ty: Float,keep: Boolean,duration: Long = 420,done: (Boolean)->Unit) {
        val old = stroke
        if (CaptureService.active?.isAutomatic()!=true) { done(false); return }
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
        val target = statusText
        val visible = floating?.visibility == View.VISIBLE && target != null
        val location=IntArray(2)
        if (visible) target!!.getLocationOnScreen(location)
        // The full panel is invisible during autoplay. Tapping its former
        // coordinates would hit the underlying Block Blast board; instead
        // cancel with a fresh stroke on the top-left system screen corner.
        val x=if(visible) location[0] + target!!.width*.5f else 2f
        val y=if(visible) location[1] + target!!.height*.5f else 2f
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
