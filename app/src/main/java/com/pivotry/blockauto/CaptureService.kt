package com.pivotry.blockauto

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import com.pivotry.blockauto.core.*
import java.util.concurrent.atomic.AtomicInteger

/** All vision/control state belongs to one worker. Android gestures belong to main. */
class CaptureService : Service() {
    companion object { @Volatile var active: CaptureService? = null }
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("BlockBlast-control")
    private lateinit var worker: Handler
    private val generation = AtomicInteger()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var physicalWidth = 0
    private var physicalHeight = 0
    @Volatile private var running = false
    @Volatile private var destroyed = false
    @Volatile private var targetApp: String? = null
    @Volatile var statusMessage = "준비 중"; private set
    private var cached: Game.Image? = null
    private var timestamp = Long.MIN_VALUE
    private var nextReadAt = 0L
    private var stableSignature = ""
    private var stableAt = 0L
    private var lastPlanSignature = ""
    private var lastPlanChain = -1
    private var lastPlanMisses = -1
    private var lastDetectionCode = ""
    private val scoreTracker = ScoreTracker()
    private var noForegroundSince = 0L

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); thread.start(); worker = Handler(thread.looper); active = this
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { halt("화면 공유 종료"); stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("capture","자동 플레이",NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this,0,Intent(this,CaptureService::class.java).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this,"capture")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Block Blast Auto")
            .setContentText("화면은 기기 안에서만 분석합니다 · 눌러 종료")
            .setContentIntent(stop).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"종료",stop).build()).build()
        startForeground(100,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val result = intent?.getIntExtra("resultCode",Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data",Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("data")
        if (result != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            physicalWidth = bounds.width(); physicalHeight = bounds.height()
            require(physicalWidth <= 2160 && physicalHeight <= 4800) { "Display too large" }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(result,data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { halt("화면 공유가 중단되었습니다"); stopSelf() }
                override fun onCapturedContentResize(width: Int,height: Int) {
                    if (width != physicalWidth || height != physicalHeight) {
                        halt("화면 크기 변경 · 전체 화면 공유를 다시 시작하세요"); stopSelf()
                    }
                }
            },main)
            reader = ImageReader.newInstance(physicalWidth,physicalHeight,PixelFormat.RGBA_8888,3)
            display = projection!!.createVirtualDisplay("BlockBlastVision",physicalWidth,physicalHeight,resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,main)
            status("게임을 열고 ▶ 추천 시작을 누르세요")
            worker.post(loop)
        } catch (e: Exception) { status("화면 공유 실패: " + e.javaClass.simpleName); stopSelf() }
        return START_NOT_STICKY
    }
    fun isRunning() = running
    fun setDisplayedCombo(value: Int) {
        worker.post {
            scoreTracker.seedDisplayedCombo(value)
            stableSignature = ""
            lastPlanSignature = ""
            status("콤보 기준 설정: " + value)
        }
    }
    fun toggle() {
        if (running) { halt("추천 정지"); return }
        if (AutoService.live == null) { status("접근성 서비스를 켜주세요"); return }
        val epoch = generation.incrementAndGet()
        running = true; targetApp = null
        AutoService.live?.setRunning(true)
        worker.post {
            if (!valid(epoch)) return@post
            stableSignature = ""; lastPlanSignature = ""; noForegroundSince = 0L
            scoreTracker.reset()
            scoreTracker.seedDisplayedCombo(getSharedPreferences("score",MODE_PRIVATE).getInt("display_combo",0))
            status("게임판 확인 중")
        }
    }
    fun halt(reason: String) {
        running = false; generation.incrementAndGet(); targetApp = null
        main.post { AutoService.live?.clearGuide(true); AutoService.live?.setRunning(false) }
        status(reason)
    }
    fun checkForeground() {
        val expected = targetApp ?: return
        val actual = AutoService.live?.foregroundPackage()
        if (running && actual != null && actual != expected) halt("앱 전환으로 정지 · 게임에서 다시 시작하세요")
    }
    private fun valid(epoch: Int) = running && !destroyed && generation.get() == epoch
    private fun status(message: String) {
        if (statusMessage != message) {
            statusMessage = message
            android.util.Log.i("BlockBlastAuto",message)
            AutoService.live?.setStatus(message)
        }
    }
    private fun stable(signature: String,now: Long): Boolean {
        if (signature != stableSignature) { stableSignature = signature; stableAt = now; return false }
        return now - stableAt >= 200
    }
    private val loop = object : Runnable {
        override fun run() {
            if (destroyed) return
            try { if (running) tick() }
            catch (e: Exception) { halt("분석 중단: " + e.javaClass.simpleName) }
            if (!destroyed) worker.postDelayed(this,130)
        }
    }
    private fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now < nextReadAt) return
        val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
        if (bounds.width() != physicalWidth || bounds.height() != physicalHeight) { halt("회전 감지 · 세로 화면에서 공유를 다시 시작하세요"); return }
        val img = reader?.acquireLatestImage()
        if (img != null) {
            try {
                // MediaProjection/ImageReader timestamps are not guaranteed to
                // increase across all Android vendor implementations. Some
                // streams report 0 or repeat a timestamp, leaving the SAME
                // old board cached while the game visibly changes.
                // acquireLatestImage() already gives the newest available buffer.
                // Use our own monotonic capture sequence for action freshness.
                val latest = Vision.copy(img)
                cached = latest
                timestamp = SystemClock.elapsedRealtimeNanos()
            } finally { img.close() }
        }
        // Static screens may not produce new virtual-display buffers. Re-observe the
        // latest image, but never reuse an image from before an action across a move.
        val raw = cached ?: return
        val image = AutoService.live?.cleanCaptured(raw) ?: raw
        val epoch = generation.get()
        
        val detection = Recognizer.analyze(image)
        val frame = detection.frame
        if (!valid(epoch)) return
        if (frame == null) {
            AutoService.live?.clearGuide()
            lastPlanSignature = ""
            val code = detection.code()
            if (code != lastDetectionCode) {
                lastDetectionCode = code
                android.util.Log.i("BlockBlastAuto","recognition " + code + " image=" + image.width + "x" + image.height)
            }
            stableSignature = ""
            status(recognitionMessage(detection))
            return
        }
        lastDetectionCode = ""
        if (frame.shapes.isEmpty()) { AutoService.live?.clearGuide(); lastPlanSignature = ""; stableSignature = ""; status("블록 대기 중 또는 게임 종료"); return }
        if (!stable(frame.signature(),now)) { status("판·블록 " + frame.shapes.size + "개 확인 · 화면 안정화 중"); return }
        scoreTracker.observe(frame)
        // SurfaceView-based games can expose no accessibility root.
        // Stable visual recognition may still establish the game is on screen.
        val app = AutoService.live?.foregroundPackage()
        if (app == null) {
            if (noForegroundSince == 0L) noForegroundSince = now
            if (now - noForegroundSince < 400L) {
                status("게임 화면 검증 중")
                return
            }
        } else noForegroundSince = 0L
        if (targetApp != null && app != null && targetApp != app) { halt("앱 전환 감지"); return }
        val signature = frame.signature()
        val chain = scoreTracker.combo()
        val missed = scoreTracker.misses()
        if (signature == lastPlanSignature && chain == lastPlanChain && missed == lastPlanMisses) return
        status("최적 수순 탐색 중 · 블록 " + frame.shapes.size + "개")
        // Manual advice can use a larger search budget; unchanged frames are
        // memoized above so no costly re-search happens each capture frame.
        val result = Planner.best(frame.board,frame.shapes.map { it.piece },500,chain,missed)
        lastPlanSignature = signature
        lastPlanChain = chain
        lastPlanMisses = missed
        if (!valid(epoch)) return
        if (result == null || result.moves.size != frame.shapes.size) {
            AutoService.live?.clearGuide(true)
            status("현재 손패 전체의 안전한 배치를 찾지 못했습니다")
            return
        }
        val move = result.moves.first()
        scoreTracker.expect(frame,move.piece,move.x,move.y)
        val piece = frame.shapes[move.piece].piece
        val cells = piece.xs.indices.map { i -> Pair(move.x + piece.xs[i],move.y + piece.ys[i]) }
        AutoService.live?.showGuide(frame.left,frame.top,frame.cell,cells,frame.width,frame.height)
        status("고득점 추천 " + (frame.shapes[move.piece].slot+1) + "번: " + (move.x+1) + "열 " + (move.y+1) + "행 · 추적 콤보 " + scoreTracker.combo())
    }
    private fun recognitionMessage(result: Recognizer.Detection): String = when (result.issue) {
        Recognizer.Issue.FORMAT -> "화면 크기 불일치 · 전체 세로 화면을 공유하세요"
        Recognizer.Issue.BLACK_FRAME -> "검은 화면 수신 · 전체 화면 공유를 다시 시작하세요"
        Recognizer.Issue.GRID -> "8×8 격자 미검출 · 게임판이 보이는지 확인하세요"
        Recognizer.Issue.BOARD_REFERENCE -> "판 인식됨 · 빈칸 기준 색상 확인 실패"
        Recognizer.Issue.BOARD_CELL -> "판 인식됨 · ${result.row+1}행 ${result.column+1}열 색상 불확실"
        Recognizer.Issue.BOARD_ANIMATION -> "판 인식됨 · 줄 삭제 효과가 끝나기를 기다리는 중"
        Recognizer.Issue.TRAY -> "추천 대기 · ${result.slot+1}번 블록을 아직 못 읽음 · " + when (result.reason) {
            "OUTSIDE_CAPTURE" -> "공유 범위 부족"
            "UNSUPPORTED_COLOR", "OCCLUDED_OR_UNKNOWN_COLOR" -> "색상 또는 가림 확인"
            else -> "칸 경계·모양 확인"
        }
        else -> "인식 확인 중"
    }
    override fun onDestroy() {
        destroyed = true; halt("화면 공유 종료"); if (active === this) active = null
        worker.removeCallbacks(loop)
        worker.post {
            display?.release(); display = null
            reader?.close(); reader = null
            cached = null
            projection?.stop(); projection = null
            thread.quitSafely()
        }
        super.onDestroy()
    }
}
