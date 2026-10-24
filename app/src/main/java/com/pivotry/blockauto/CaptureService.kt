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
import kotlin.math.abs
import kotlin.math.min

/** All vision/control state belongs to one worker. Android gestures belong to main. */
class CaptureService : Service() {
    companion object { @Volatile var active: CaptureService? = null }
    private enum class Phase { IDLE, PICKED, ALIGN, VERIFY }
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
    @Volatile var verifiedMoves = 0; private set
    private var phase = Phase.IDLE
    private var inFlight = false
    private var cached: Game.Image? = null
    private var timestamp = Long.MIN_VALUE
    private var actionFloor = Long.MIN_VALUE
    private var nextReadAt = 0L
    private var phaseAt = 0L
    private var stableSignature = ""
    private var stableAt = 0L
    private var before: Game.Frame? = null
    private var beforeImage: Game.Image? = null
    private var selected: Game.Shape? = null
    private var verifier: MoveVerifier? = null
    private var targetX = 0f
    private var targetY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var observed: DragAlignment.Origin? = null
    private var observedAt = 0L
    private var corrections = 0
    private var retrySignature = ""
    private var retries = 0
    private var lastDetectionCode = ""

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
            status("게임을 열고 ▶ 자동 시작을 누르세요")
            worker.post(loop)
        } catch (e: Exception) { status("화면 공유 실패: " + e.javaClass.simpleName); stopSelf() }
        return START_NOT_STICKY
    }
    fun isRunning() = running
    fun toggle() {
        if (running) { halt("자동 플레이 정지"); return }
        if (AutoService.live == null) { status("접근성 서비스를 켜주세요"); return }
        val epoch = generation.incrementAndGet()
        running = true; targetApp = null; verifiedMoves = 0
        AutoService.live?.setRunning(true)
        worker.post {
            if (!valid(epoch)) return@post
            phase = Phase.IDLE; inFlight = false; beforeImage = null; before = null; verifier = null
            stableSignature = ""; observed = null; retries = 0; retrySignature = ""
            actionFloor = Long.MIN_VALUE; phaseAt = SystemClock.uptimeMillis()
            status("게임판 확인 중")
        }
    }
    fun halt(reason: String) {
        running = false; generation.incrementAndGet(); targetApp = null
        main.post { AutoService.live?.abortHold(); AutoService.live?.setRunning(false) }
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
            try { if (running && !inFlight) tick() }
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
            try { if (img.timestamp > timestamp) { cached = Vision.copy(img); timestamp = img.timestamp } }
            finally { img.close() }
        }
        // Static screens may not produce new virtual-display buffers. Re-observe the
        // latest image, but never reuse an image from before an action across a move.
        val image = cached ?: return
        if (timestamp <= actionFloor) {
            if (now-phaseAt > 3500) halt("새 화면을 받지 못했습니다 · 공유를 다시 시작하세요")
            return
        }
        val epoch = generation.get()
        if (phase == Phase.PICKED || phase == Phase.ALIGN) { align(image,now,epoch); return }
        val detection = Recognizer.analyze(image)
        val frame = detection.frame
        if (!valid(epoch)) return
        if (frame == null) {
            val code = detection.code()
            if (code != lastDetectionCode) {
                lastDetectionCode = code
                android.util.Log.i("BlockBlastAuto","recognition " + code + " image=" + image.width + "x" + image.height)
            }
            if (phase == Phase.VERIFY && now-phaseAt > 3500) halt("설치 확인 중단 · " + recognitionMessage(detection))
            else if (phase == Phase.IDLE) { stableSignature = ""; status(recognitionMessage(detection)) }
            return
        }
        lastDetectionCode = ""
        if (phase == Phase.VERIFY) {
            if (stable(frame.signature(),now) && verifier!!.matches(frame)) {
                verifiedMoves++; status("설치 확인 · " + verifiedMoves + "수")
                phase = Phase.IDLE; stableSignature = ""; beforeImage = null; before = null
                verifier = null; retries = 0; retrySignature = ""; actionFloor = Long.MIN_VALUE
            } else if (now-phaseAt > 3200) {
                if (verifier!!.unchanged(frame) && retries < 1) {
                    retries++; retrySignature = frame.signature(); phase = Phase.IDLE; stableSignature = ""
                    actionFloor = Long.MIN_VALUE; status("설치 안 됨 · 새 화면으로 1회 재시도")
                } else halt("설치 결과 불일치 · 잘못된 연속 조작을 막기 위해 정지")
            }
            return
        }
        if (frame.shapes.isEmpty()) { stableSignature = ""; status("블록 대기 중 또는 게임 종료"); return }
        if (!stable(frame.signature(),now)) return
        val app = AutoService.live?.foregroundPackage() ?: return
        if (targetApp != null && targetApp != app) { halt("앱 전환 감지"); return }
        val result = Planner.best(frame.board,frame.shapes.map { it.piece },180)
        if (!valid(epoch)) return
        if (result == null) { halt("인식한 블록의 합법적인 배치가 없습니다"); return }
        if (retrySignature.isNotEmpty() && frame.signature() != retrySignature) { retries = 0; retrySignature = "" }
        val move = result.moves.first()
        val shape = frame.shapes[move.piece]
        before = frame; beforeImage = image; selected = shape
        verifier = MoveVerifier(frame,move.piece,move.x,move.y)
        targetX = frame.left+move.x*frame.cell; targetY = frame.top+move.y*frame.cell
        android.util.Log.i("BlockBlastAuto","plan board="+java.lang.Long.toHexString(frame.board)+" slot="+shape.slot+" target="+targetX+","+targetY+" cell="+frame.cell)
        targetApp = app; corrections = 0; observed = null
        fingerX = frame.width*.5f
        fingerY = min(frame.height-frame.cell*.7f,frame.top+8*frame.cell+shape.piece.h*frame.cell*.5f+2*frame.cell)
        status("블록 " + (shape.slot+1) + " 잡기 · 위치 측정")
        gesture(epoch,Phase.PICKED) { service,done ->
            service.beginDrag(shape.pickX*scaleX(frame),shape.pickY*scaleY(frame),fingerX*scaleX(frame),fingerY*scaleY(frame),app,done)
        }
    }
    private fun recognitionMessage(result: Recognizer.Detection): String = when (result.issue) {
        Recognizer.Issue.FORMAT -> "화면 크기 불일치 · 전체 세로 화면을 공유하세요"
        Recognizer.Issue.BLACK_FRAME -> "검은 화면 수신 · 전체 화면 공유를 다시 시작하세요"
        Recognizer.Issue.GRID -> "8×8 격자 미검출 · 게임판이 보이는지 확인하세요"
        Recognizer.Issue.BOARD_REFERENCE -> "판 인식됨 · 빈칸 기준 색상 확인 실패"
        Recognizer.Issue.BOARD_CELL -> "판 인식됨 · ${result.row+1}행 ${result.column+1}열 색상 불확실"
        Recognizer.Issue.BOARD_ANIMATION -> "판 인식됨 · 줄 삭제 효과가 끝나기를 기다리는 중"
        Recognizer.Issue.TRAY -> "판 인식됨 · ${result.slot+1}번 블록 인식 대기 · " + when (result.reason) {
            "OUTSIDE_CAPTURE" -> "공유 범위 부족"
            "UNSUPPORTED_COLOR", "OCCLUDED_OR_UNKNOWN_COLOR" -> "색상 또는 가림 확인"
            else -> "칸 경계·모양 확인"
        }
        else -> "인식 확인 중"
    }
    private fun align(image: Game.Image,now: Long,epoch: Int) {
        val frame = before ?: return
        val shape = selected ?: return
        val found = if (phase == Phase.PICKED) DragAlignment.locate(image,beforeImage!!,frame,shape,fingerX,fingerY)
            else DragAlignment.locateNear(image,beforeImage!!,frame,shape,targetX,targetY)
        if (!valid(epoch)) return
        if (found == null) {
            observed = null
            if (now-phaseAt > 2200) halt("잡힌 블록 위치가 불확실해 정지 · 블록을 원위치로 돌립니다")
            return
        }
        val old = observed
        if (old == null || abs(old.x-found.x)>frame.cell*.08 || abs(old.y-found.y)>frame.cell*.08) {
            observed = found; observedAt = now; return
        }
        if (now-observedAt < 180) return
        val dx = targetX-found.x; val dy = targetY-found.y
        android.util.Log.i("BlockBlastAuto","align "+phase+" origin="+found.x+","+found.y+" delta="+dx+","+dy)
        if (phase == Phase.ALIGN && abs(dx)<frame.cell*.13 && abs(dy)<frame.cell*.13) {
            status("정렬 확인 · 블록 놓기")
            gesture(epoch,Phase.VERIFY) { service,done -> service.moveHeld(fingerX*scaleX(frame),fingerY*scaleY(frame),false,150,done) }
        } else {
            if (corrections++ >= 3) { halt("위치 보정 실패 · 정지"); return }
            fingerX += dx; fingerY += dy
            if (fingerX<2 || fingerX>frame.width-2 || fingerY<2 || fingerY>frame.height-2) { halt("터치 범위를 벗어나는 배치 · 정지"); return }
            status("블록 위치 보정 " + corrections)
            gesture(epoch,Phase.ALIGN) { service,done -> service.moveHeld(fingerX*scaleX(frame),fingerY*scaleY(frame),true,420,done) }
        }
    }
    private fun scaleX(frame: Game.Frame) = physicalWidth.toFloat()/frame.width
    private fun scaleY(frame: Game.Frame) = physicalHeight.toFloat()/frame.height
    private fun gesture(epoch: Int,next: Phase,action: (AutoService,(Boolean)->Unit)->Unit) {
        inFlight = true; actionFloor = timestamp
        main.post {
            if (!valid(epoch)) return@post
            val service = AutoService.live
            if (service == null || (targetApp != null && service.foregroundPackage() != targetApp)) { halt("접근성 또는 게임 화면 연결 끊김"); return@post }
            action(service) { ok ->
                worker.post {
                    if (!valid(epoch)) return@post
                    inFlight = false
                    if (!ok) { halt("터치가 취소되었습니다 · 다시 시작하세요"); return@post }
                    phase = next; phaseAt = SystemClock.uptimeMillis(); nextReadAt = phaseAt+160
                    stableSignature = ""; observed = null
                }
            }
        }
    }
    override fun onDestroy() {
        destroyed = true; halt("화면 공유 종료"); if (active === this) active = null
        worker.removeCallbacks(loop)
        worker.post {
            display?.release(); display = null
            reader?.close(); reader = null
            cached = null; beforeImage = null
            projection?.stop(); projection = null
            thread.quitSafely()
        }
        super.onDestroy()
    }
}
