package com.pivotry.blockauto

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONObject
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
    @Volatile private var automatic = false
    fun isAutomatic()=automatic
    fun setAutomatic(enable:Boolean) {
        if(running) halt("모드 변경 · 다시 ▶ 시작을 누르세요")
        automatic=enable
        getSharedPreferences("play_mode",MODE_PRIVATE).edit().putBoolean("auto",enable).apply()
        AutoService.live?.setAutomatic(enable)
        worker.post {lastRecommendedSignature=""}
        status(if(enable) "자동 플레이 모드 · ▶ 시작" else "하이라이트 추천 모드 · ▶ 시작")
    }
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
    private var lastRecommendedSignature=""
    private var lastRecommendedCombo=-1
    private var lastRecommendedMisses=-1
    private var before: Game.Frame? = null
    private var beforeImage: Game.Image? = null
    private var probeImage: Game.Image? = null
    private var probeDX = 0f
    private var probeDY = 0f
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
    private val scoreTracker = ScoreTracker()
    private val comboVision by lazy { ComboVision() }
    private var observedCombo=-1
    private var observedComboAt=0L
    private var boardTop=0f
    private var boardCell=0f
    private var noForegroundSince = 0L
    @Volatile private var failureTrace: FailureTrace? = null

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); thread.start(); worker = Handler(thread.looper); active = this
        automatic=getSharedPreferences("play_mode",MODE_PRIVATE).getBoolean("auto",false)
        AutoService.live?.setAutomatic(automatic)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { halt("화면 공유 종료"); stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("capture","블록 추천 및 자동 플레이",NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this,0,Intent(this,CaptureService::class.java).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this,"capture")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Block Blast Advisor + Auto")
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
            status("게임을 열고 모드 선택 후 ▶ 시작을 누르세요")
            worker.post(loop)
        } catch (e: Exception) { status("화면 공유 실패: " + e.javaClass.simpleName); stopSelf() }
        return START_NOT_STICKY
    }
    fun isRunning() = running
    fun toggle() {
        if (running) { halt("플레이 정지"); return }
        if (AutoService.live == null) { status("접근성 서비스를 켜주세요"); return }
        val epoch = generation.incrementAndGet()
        running = true; targetApp = null; verifiedMoves = 0
        AutoService.live?.setRunning(true)
        worker.post {
            if (!valid(epoch)) return@post
            phase = Phase.IDLE; inFlight = false; beforeImage = null; probeImage = null; before = null; verifier = null; failureTrace = null
            stableSignature = ""; lastRecommendedSignature=""; observed = null; retries = 0; retrySignature = ""; noForegroundSince = 0L
            scoreTracker.reset()
            observedCombo=-1; observedComboAt=0L; boardTop=0f; boardCell=0f
            actionFloor = Long.MIN_VALUE; phaseAt = SystemClock.uptimeMillis()
            status(if(automatic) "자동 플레이 · 게임판 확인 중" else "추천 모드 · 게임판 확인 중")
        }
    }
    fun halt(reason: String) {
        running = false; generation.incrementAndGet(); targetApp = null
        main.post { AutoService.live?.abortHold(); AutoService.live?.clearGuide(true); AutoService.live?.setRunning(false) }
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
        if (timestamp <= actionFloor) {
            if (now-phaseAt > 3500) halt("새 화면을 받지 못했습니다 · 공유를 다시 시작하세요")
            return
        }
        val epoch = generation.get()
        scanDisplayedCombo(image,now,epoch)
        // During a held gesture the board is obscured by a moving piece.
        // Measure the held object instead of re-running static board recognition.
        if (phase == Phase.PICKED || phase == Phase.ALIGN) { align(image,now,epoch); return }

        val detection = Recognizer.analyze(image)
        val frame = detection.frame
        if (!valid(epoch)) return
        if(frame!=null){boardTop=frame.top;boardCell=frame.cell}
        if (frame == null) {
            AutoService.live?.clearGuide()
            val code = detection.code()
            if (code != lastDetectionCode) {
                lastDetectionCode = code
                android.util.Log.i("BlockBlastAuto","recognition " + code + " image=" + image.width + "x" + image.height)
            }
            lastRecommendedSignature=""
            if (phase == Phase.VERIFY && now-phaseAt > 3500) halt("설치 확인 중단 · " + recognitionMessage(detection))
            else if (phase == Phase.IDLE) { stableSignature = ""; status(recognitionMessage(detection)) }
            return
        }
        lastDetectionCode = ""
        if (phase == Phase.IDLE) scoreTracker.observe(frame)
        if (phase == Phase.VERIFY) {
            if (stable(frame.signature(),now) && verifier!!.matches(frame)) {
                verifiedMoves++; status("설치 확인 · " + verifiedMoves + "수")
                phase = Phase.IDLE; stableSignature = ""; beforeImage = null; probeImage = null; before = null
                verifier = null; retries = 0; retrySignature = ""; actionFloor = Long.MIN_VALUE
            } else if (now-phaseAt > 3200) {
                if (verifier!!.unchanged(frame) && retries < 1) {
                    retries++; retrySignature = frame.signature(); phase = Phase.IDLE; stableSignature = ""
                    actionFloor = Long.MIN_VALUE; status("설치 안 됨 · 새 화면으로 1회 재시도")
                } else halt("설치 결과 불일치 · 잘못된 연속 조작을 막기 위해 정지")
            }
            return
        }
        if (frame.shapes.isEmpty()) { AutoService.live?.clearGuide(); lastRecommendedSignature=""; stableSignature = ""; status("블록 대기 중 또는 게임 종료"); return }
        if (!stable(frame.signature(),now)) { status("판·블록 " + frame.shapes.size + "개 확인 · 화면 안정화 중"); return }
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
        if(!automatic && frame.signature()==lastRecommendedSignature &&
            scoreTracker.combo()==lastRecommendedCombo &&
            scoreTracker.misses()==lastRecommendedMisses)return
        status("배치 계산 중 · 블록 " + frame.shapes.size + "개")
        val result = Planner.best(frame.board,frame.shapes.map { it.piece },if(automatic) 220 else 500,scoreTracker.combo(),scoreTracker.misses())
        if (!valid(epoch)) return
        if(!automatic) {
            lastRecommendedSignature=frame.signature()
            lastRecommendedCombo=scoreTracker.combo()
            lastRecommendedMisses=scoreTracker.misses()
        }
        if (result == null || result.moves.size != frame.shapes.size) {
            // A partial recommendation is not safe for unattended autoplay:
            // the remaining visible piece(s) may already be impossible to fit.
            AutoService.live?.clearGuide(true)
            if(automatic) halt("현재 손패 전체 배치 불가 · 자동 조작 안전 정지") else status("손패 전체 배치 불가 · 추천 중단")
            return
        }
        if (retrySignature.isNotEmpty() && frame.signature() != retrySignature) { retries = 0; retrySignature = "" }
        val move = result.moves.first()
        val shape = frame.shapes[move.piece]
        // Record the expected board before input. Only an observed match
        // advances the estimated combo or permits another placement.
        scoreTracker.expect(frame,move.piece,move.x,move.y)
        if(!automatic) {
            val p=shape.piece
            val cells=p.xs.indices.map { i -> Pair(move.x+p.xs[i],move.y+p.ys[i]) }
            AutoService.live?.showGuide(frame.left,frame.top,frame.cell,cells,frame.width,frame.height)
            status("추천 "+(shape.slot+1)+"번 · "+(move.x+1)+"열 "+(move.y+1)+"행 · 콤보 "+scoreTracker.combo())
            return
        }
        before = frame; beforeImage = image; probeImage = null
        probeDX = 0f; probeDY = 0f; selected = shape
        verifier = MoveVerifier(frame,move.piece,move.x,move.y)
        targetX = frame.left+move.x*frame.cell
        targetY = frame.top+move.y*frame.cell
        targetApp = app; corrections = 0; observed = null
        // Never let guidance outlines become part of the captured game state.
        AutoService.live?.clearGuide(true)
        fingerX = frame.width*.5f
        fingerY = min(frame.height-frame.cell*.7f,
            frame.top+8*frame.cell+shape.piece.h*frame.cell*.5f+2*frame.cell)
        status("자동 배치 · 블록 " + (shape.slot+1) + "번 잡기 · 콤보 " + scoreTracker.combo())
        gesture(epoch,Phase.PICKED) { service,done ->
            service.beginDrag(shape.pickX*scaleX(frame),shape.pickY*scaleY(frame),
                fingerX*scaleX(frame),fingerY*scaleY(frame),app,done)
        }
    }
    private fun recognitionMessage(result: Recognizer.Detection): String = when (result.issue) {
        Recognizer.Issue.FORMAT -> "화면 크기 불일치 · 전체 세로 화면을 공유하세요"
        Recognizer.Issue.BLACK_FRAME -> "검은 화면 수신 · 전체 화면 공유를 다시 시작하세요"
        Recognizer.Issue.GRID -> "8×8 격자 미검출 · 게임판이 보이는지 확인하세요"
        Recognizer.Issue.BOARD_REFERENCE -> "판 인식됨 · 빈칸 기준 색상 확인 실패"
        Recognizer.Issue.BOARD_CELL -> "판 인식됨 · ${result.row+1}행 ${result.column+1}열 색상 불확실"
        Recognizer.Issue.BOARD_ANIMATION -> "판 인식됨 · 줄 삭제 효과가 끝나기를 기다리는 중"
        Recognizer.Issue.TRAY -> "자동 배치 대기 · ${result.slot+1}번 블록을 아직 못 읽음 · " + when (result.reason) {
            "OUTSIDE_CAPTURE" -> "공유 범위 부족"
            "UNSUPPORTED_COLOR", "OCCLUDED_OR_UNKNOWN_COLOR" -> "색상 또는 가림 확인"
            else -> "칸 경계·모양 확인"
        }
        else -> "인식 확인 중"
    }
    /** Only accepts explicitly labelled COMBO numbers, never the score. */
    private fun scanDisplayedCombo(image:Game.Image,now:Long,epoch:Int) {
        comboVision.scan(image,now,boardTop,boardCell) { displayed ->
            worker.post {
                if(!valid(epoch))return@post
                val previous=observedCombo
                val previousTime=observedComboAt
                observedComboAt=SystemClock.uptimeMillis()
                if(displayed==previous && observedComboAt-previousTime<3000)return@post
                observedCombo=displayed
                scoreTracker.observeDisplayedCombo(displayed)
                android.util.Log.i("BlockBlastAuto","OCR COMBO="+displayed)
            }
        }
    }
    private fun align(image: Game.Image,now: Long,epoch: Int) {
        val frame = before ?: return
        val shape = selected ?: return
        if (phase == Phase.PICKED && probeImage == null) {
            // Confirm movement while keeping the same pointer down. A static or
            // whole-cell-snapped preview must not determine our finger offset.
            probeImage = image; probeDX = frame.cell*.7f; probeDY = 0f
            if (fingerX+probeDX > frame.width-2) probeDX = -probeDX
            fingerX += probeDX
            status("잡기 확인 · 추가 움직임 측정")
            gesture(epoch,Phase.PICKED) { service,done -> service.moveHeld(fingerX*scaleX(frame),fingerY*scaleY(frame),true,320,done) }
            return
        }
        // The target-local search is intentionally strict: only that match
        // can authorize a drop. If the game snaps the held piece far away
        // (v1.3.1 diagnostic: actual origin ~523,273 vs target ~440,524),
        // recover its UNIQUE newly rendered position by a wider search and
        // correct the pointer while keeping the finger down.
        val targetMatch=if(phase==Phase.ALIGN)
            DragAlignment.locateNear(image,beforeImage!!,frame,shape,targetX,targetY)
            else null
        val found=if(phase==Phase.PICKED)
            DragAlignment.locateMoving(image,beforeImage!!,probeImage!!,frame,shape,
                fingerX,fingerY,probeDX,probeDY)
            else targetMatch ?: DragAlignment.locate(image,beforeImage!!,frame,shape,fingerX,fingerY)
        if (!valid(epoch)) return
        if (found == null) {
            observed = null
            // A single probe may land during a render transition. Reprobe
            // once in the opposite direction without releasing the pointer.
            if (phase == Phase.PICKED && corrections == 0 && now-phaseAt > 900) {
                corrections = 1
                probeImage = image
                probeDX = -frame.cell*.65f
                probeDY = 0f
                fingerX += probeDX
                status("잡기 재측정 · 블록 움직임 재확인")
                gesture(epoch,Phase.PICKED) { service,done ->
                    service.moveHeld(fingerX*scaleX(frame),fingerY*scaleY(frame),true,350,done)
                }
                return
            }
            if (now-phaseAt > 2200) {
                freezeFailure(image,"NO_HELD_MATCH")
                val stage = if (phase == Phase.PICKED) "잡기" else "판 정렬"
                halt(stage + " 위치 측정 실패 · 앱에서 진단 파일을 저장할 수 있습니다")
            }
            return
        }
        val old = observed
        if (old == null || abs(old.x-found.x)>frame.cell*.08 || abs(old.y-found.y)>frame.cell*.08) {
            observed = found; observedAt = now; return
        }
        if (now-observedAt < 180) return
        val dx = targetX-found.x; val dy = targetY-found.y
        android.util.Log.i("BlockBlastAuto","align "+phase+" match="+(if(targetMatch!=null)"TARGET" else "RECOVERY")+
            " origin="+found.x+","+found.y+" delta="+dx+","+dy)
        // Never let a weak visual match trigger ACTION_UP. The held object must
        // lie fully within the intended legal board rectangle.
        val targetWithinBoard = targetX >= frame.left-frame.cell*.08f &&
            targetY >= frame.top-frame.cell*.08f &&
            targetX + shape.piece.w*frame.cell <= frame.left+8*frame.cell+frame.cell*.08f &&
            targetY + shape.piece.h*frame.cell <= frame.top+8*frame.cell+frame.cell*.08f
        if (!targetWithinBoard) {
            freezeFailure(image,"INVALID_TARGET")
            halt("목표 위치 오류 · 드래그 취소")
            return
        }
        // Never release based only on the more lenient occlusion-tolerant
        // recovery match. The usual high-confidence target-area match MUST
        // succeed before sending ACTION_UP.
        if (phase == Phase.ALIGN && targetMatch!=null &&
            abs(dx)<frame.cell*.13 && abs(dy)<frame.cell*.13) {
            status("정렬 확인 · 블록 놓기")
            gesture(epoch,Phase.VERIFY) { service,done -> service.moveHeld(fingerX*scaleX(frame),fingerY*scaleY(frame),false,150,done) }
        } else {
            // Allow a few bounded recovery moves for screen snap offsets.
            // No uncertainty bypass: 5 failed corrections still stop safely.
            if (corrections++ >= 5) {
                freezeFailure(image,"CORRECTION_LIMIT")
                halt("위치 보정 실패 · 진단 저장 후 정지")
                return
            }
            // An unexpectedly large correction indicates tracking the wrong
            // object or a game-specific drag offset that is not calibrated.
            if (abs(dx)>frame.cell*7f || abs(dy)>frame.cell*10f) {
                freezeFailure(image,"IMPLAUSIBLE_CORRECTION")
                halt("블록 추적 좌표 불일치 · 안전 정지")
                return
            }
            fingerX += dx; fingerY += dy
            if (fingerX<2 || fingerX>frame.width-2 || fingerY<2 || fingerY>frame.height-2) { halt("터치 범위를 벗어나는 배치 · 정지"); return }
            status("블록 위치 보정 " + corrections)
            gesture(epoch,Phase.ALIGN) { service,done -> service.moveHeld(fingerX*scaleX(frame),fingerY*scaleY(frame),true,420,done) }
        }
    }
    private fun freezeFailure(image: Game.Image,reason: String) {
        val original = beforeImage ?: return
        val frame = before ?: return
        val shape = selected ?: return
        val data = JSONObject().put("version",BuildConfig.VERSION_NAME).put("reason",reason).put("phase",phase.name)
            .put("width",image.width).put("height",image.height).put("physicalWidth",physicalWidth).put("physicalHeight",physicalHeight)
            .put("gridLeft",frame.left).put("gridTop",frame.top).put("cell",frame.cell).put("board",java.lang.Long.toHexString(frame.board))
            .put("slot",shape.slot).put("piece",shape.piece.key).put("pickX",shape.pickX).put("pickY",shape.pickY)
            .put("fingerX",fingerX).put("fingerY",fingerY).put("probeDX",probeDX).put("probeDY",probeDY).put("targetX",targetX).put("targetY",targetY).put("uptimeMillis",SystemClock.uptimeMillis())
        failureTrace = FailureTrace(original,image,data.toString(2),probeImage)
        android.util.Log.i("BlockBlastAuto","held match failed " + data.toString())
    }
    fun hasDiagnostics() = failureTrace != null
    fun saveDiagnostics(uri: Uri,done: (String?)->Unit) {
        val trace = failureTrace
        if (trace == null) { main.post { done("저장할 위치 측정 실패 기록이 없습니다") }; return }
        halt("진단 저장 · 자동 플레이 정지")
        worker.post {
            val error = try {
                val output = contentResolver.openOutputStream(uri,"w") ?: throw java.io.IOException("출력 파일을 열 수 없습니다")
                output.use { stream -> trace.writeZip(stream) { image,destination ->
                    val bitmap = Bitmap.createBitmap(image.pixels,image.width,image.height,Bitmap.Config.ARGB_8888)
                    try { if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,destination)) throw java.io.IOException("PNG 인코딩 실패") }
                    finally { bitmap.recycle() }
                } }
                null
            } catch (e: Exception) { "진단 저장 실패: " + e.javaClass.simpleName }
            main.post { done(error) }
        }
    }
    private fun scaleX(frame: Game.Frame) = physicalWidth.toFloat()/frame.width
    private fun scaleY(frame: Game.Frame) = physicalHeight.toFloat()/frame.height
    private fun gesture(epoch: Int,next: Phase,action: (AutoService,(Boolean)->Unit)->Unit) {
        inFlight = true; actionFloor = timestamp
        main.post {
            if (!valid(epoch)) return@post
            val service = AutoService.live
            val actualApp = service?.foregroundPackage()
            if (service == null || (targetApp != null && actualApp != null && actualApp != targetApp)) { halt("접근성 또는 게임 화면 연결 끊김"); return@post }
            action(service) { ok ->
                worker.post {
                    if (!valid(epoch)) return@post
                    inFlight = false
                    if (!ok) { halt("터치 실패 · " + service.lastGestureError.ifEmpty { "입력 연결을 확인하세요" }); return@post }
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
            cached = null; beforeImage = null; probeImage = null
            comboVision.close()
            projection?.stop(); projection = null
            thread.quitSafely()
        }
        super.onDestroy()
    }
}
