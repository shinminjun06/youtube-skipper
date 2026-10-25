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
    /** Avoid rescanning identical buffers; never reuse across NEW captures. */
    private val recognitionCache = FrameRecognitionCache()
    // Worker-only cache: ONLY grid geometry, not occupancy or tray contents.
    private var geometryHint: Game.Frame? = null
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
    // Last FULL-SHAPE pixel-confirmed sprite origin; never a fallback proxy.
    // Only this evidence can seed narrow prediction on a NEW capture.
    private var trustedHeldOrigin: DragAlignment.Origin? = null
    private var lastCorrectionTrusted = false
    private var roiTightMatches=0
    private var roiWideMatches=0
    private var roiFullFallbacks=0
    private var observedAt = 0L
    private var corrections = 0
    private var retrySignature = ""
    private var retries = 0
    private var lastDetectionCode = ""
    private val scoreTracker = ScoreTracker()
    /** Learned only after the independently verified board transition. */
    private val dragCalibration = DragCalibration()
    /** Per-stroke measured sprite travel / finger travel; NEVER authorizes release. */
    private val dragResponse = DragResponse()
    /** Start at v1.4.2-safe speed; accelerate only after verified clean moves. */
    private val pace = AdaptivePace()
    private var usedBoardOverlapRecovery = false
    private var lastBoardOverlapShape: String? = null
    private var usedComboClearPreview = false
    private var usedLineGlowPreview = false
    private var usedDiagonalLineClearPreview = false
    private var usedBevelZigzagRecovery = false
    private var lastServoFailureDetail: String? = null
    private var lastCorrectionOrigin: DragAlignment.Origin? = null
    private var lastCorrectionStepX=0f
    private var lastCorrectionStepY=0f
    private var pendingDragSample: DragCalibration.Observation? = null
    private var directDragUsed = false
    private var directDragCount = 0
    private var stagedDragCount = 0
    private var verifiedDirectCount = 0
    private var verifiedStagedCount = 0
    private var motionStartedAt = 0L
    private var motionGestureCount = 0
    private var verifiedGestureTotal = 0
    private var verifiedMotionMillisTotal = 0L
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
            .setContentText("자동 실행 중 조작창은 숨김 · 알림 종료로 정지")
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
            stableSignature = ""; lastRecommendedSignature=""; observed = null
            trustedHeldOrigin=null; lastCorrectionTrusted=false
            roiTightMatches=0; roiWideMatches=0; roiFullFallbacks=0
            retries = 0; retrySignature = ""; noForegroundSince = 0L
            geometryHint=null
            recognitionCache.reset()
            scoreTracker.reset()
            dragCalibration.reset()
            dragResponse.reset()
            pace.reset()
            lastCorrectionOrigin=null
            lastServoFailureDetail=null
            usedBoardOverlapRecovery=false
            lastBoardOverlapShape=null
            usedComboClearPreview=false
            usedLineGlowPreview=false
            usedDiagonalLineClearPreview=false
            usedBevelZigzagRecovery=false
            pendingDragSample=null
            directDragUsed=false; directDragCount=0; stagedDragCount=0
            verifiedDirectCount=0; verifiedStagedCount=0
            motionStartedAt=0L; motionGestureCount=0
            verifiedGestureTotal=0; verifiedMotionMillisTotal=0L
            observedCombo=-1; observedComboAt=0L; boardTop=0f; boardCell=0f
            actionFloor = Long.MIN_VALUE; phaseAt = SystemClock.uptimeMillis()
            status(if(automatic) "자동 플레이 · 게임판 확인 중" else "추천 모드 · 게임판 확인 중")
        }
    }
    fun halt(reason: String) {
        running = false; generation.incrementAndGet(); targetApp = null
        if(::worker.isInitialized) worker.post {pace.uncertainty()}
        // The pending measurement must not survive an interrupted gesture.
        if(::worker.isInitialized) worker.post {pendingDragSample=null}
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
        return now - stableAt >= (if(automatic)pace.boardStableMs() else 200)
    }
    private val loop = object : Runnable {
        override fun run() {
            if (destroyed) return
            try { if (running && !inFlight) tick() }
            catch (e: Exception) { halt("분석 중단: " + e.javaClass.simpleName) }
            if (!destroyed) worker.postDelayed(this,
                if(automatic)pace.framePollMs().toLong() else 130L)
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

        // Static virtual displays may return no new ImageReader frame. In
        // automatic mode the guide overlay is hidden; if image===raw, cached
        // recognition is pixel-identical. Preserve elapsed-time stability
        // checks but avoid re-running grid and tray detection on those pixels.
        // A NEW buffer, even with identical colors, is ALWAYS analyzed again.
        val detection = recognitionCache.reuse(raw,automatic,image === raw,img != null)
            ?: Recognizer.analyze(image,geometryHint).also { found ->
                if(automatic && image === raw) recognitionCache.remember(raw,found)
                else recognitionCache.reset()
            }
        val frame = detection.frame
        if(frame!=null) geometryHint=frame
        else if(detection.issue==Recognizer.Issue.GRID ||
                detection.issue==Recognizer.Issue.FORMAT ||
                detection.issue==Recognizer.Issue.BLACK_FRAME) geometryHint=null
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
                verifiedMoves++
                // Only an actual matching board + consumed tray slot can
                // advance speed. Android gesture completion alone cannot.
                pace.verified(corrections,motionGestureCount)
                android.util.Log.i("BlockBlastAuto",
                    "pace verified tier="+pace.tier()+" clean="+pace.cleanPlacements()+
                    " heldStableMs="+pace.heldStableMs()+
                    " firstTravelMs="+pace.firstTravelMs())
                pendingDragSample?.let { sample ->
                    if(dragCalibration.recordVerified(sample)) {
                        android.util.Log.i("BlockBlastAuto",
                            "verified calibration slot="+sample.slot+" shape="+sample.pieceKey+
                            " agreements="+dragCalibration.confidence(sample.slot,sample.pieceKey)+
                            " total="+dragCalibration.confirmedSamples())
                    }
                }
                pendingDragSample=null
                if(directDragUsed) verifiedDirectCount++ else verifiedStagedCount++
                val motionDuration=if(motionStartedAt>0) now-motionStartedAt else 0
                verifiedMotionMillisTotal+=motionDuration
                verifiedGestureTotal+=motionGestureCount
                val avgGestures=if(verifiedMoves>0) verifiedGestureTotal/(verifiedMoves.toFloat()) else 0f
                android.util.Log.i("BlockBlastAuto",
                    "VERIFIED mode="+(if(directDragUsed)"calibrated" else "staged")+
                    " gestures="+motionGestureCount+" durationMs="+motionDuration+
                    " corrections="+corrections+" averageGestureCount="+avgGestures+
                    " verifiedDirect="+verifiedDirectCount+
                    " verifiedStaged="+verifiedStagedCount+
                    " pace="+pace.tier())
                status("설치 "+verifiedMoves+"수 · 속도 "+pace.tier()+
                    " · 직접 "+verifiedDirectCount+" / 기본 "+verifiedStagedCount+
                    " · 평균 제스처 "+String.format(java.util.Locale.US,"%.1f",avgGestures))
                phase = Phase.IDLE; stableSignature = ""; beforeImage = null; probeImage = null; before = null
                verifier = null; retries = 0; retrySignature = ""; actionFloor = Long.MIN_VALUE
            } else if (now-phaseAt > 3200) {
                pendingDragSample=null
                if (verifier!!.unchanged(frame) && retries < 1) {
                    // A visually unchanged board after release is a real
                    // game rejection; restore known-good timings for retry.
                    pace.uncertainty()
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
        pendingDragSample=null
        usedBoardOverlapRecovery=false
        lastBoardOverlapShape=null
        usedComboClearPreview=false
        usedLineGlowPreview=false
        usedDiagonalLineClearPreview=false
        usedBevelZigzagRecovery=false
        dragResponse.reset();lastCorrectionOrigin=null
        probeDX = 0f; probeDY = 0f; selected = shape
        verifier = MoveVerifier(frame,move.piece,move.x,move.y)
        targetX = frame.left+move.x*frame.cell
        targetY = frame.top+move.y*frame.cell
        targetApp = app; corrections = 0; observed = null
        trustedHeldOrigin=null; lastCorrectionTrusted=false
        roiTightMatches=0; roiWideMatches=0; roiFullFallbacks=0
        // Never let guidance outlines become part of the captured game state.
        AutoService.live?.clearGuide(true)
        // Once TWO independently verified placements agree on this slot's
        // shape-normalized finger/sprite offset, predict the FINAL pointer
        // location and drag there in one continuing gesture. The finger
        // remains held until strict target preview and board verification.
        // Invalid/offscreen/uncertain predictions fall back to the cold path.
        val predicted=dragCalibration.predict(shape.slot,shape.piece,frame.cell,
            targetX,targetY,frame.width,frame.height)
        motionStartedAt=now
        motionGestureCount=0
        directDragUsed=predicted!=null
        if(predicted==null && dragCalibration.confidence(shape.slot)>0) {
            android.util.Log.i("BlockBlastAuto",
                "direct drag rejected: slot="+shape.slot+" shape="+shape.piece.key+
                " exactShapeConfidence="+dragCalibration.confidence(shape.slot,shape.piece.key)+
                " target="+targetX+","+targetY+" screen="+frame.width+"x"+frame.height)
        }
        if(predicted!=null) {
            directDragCount++
            fingerX=predicted[0]; fingerY=predicted[1]
            status("학습형 직접 이동 · 블록 "+(shape.slot+1)+"번 · 목표 검증 중")
            android.util.Log.i("BlockBlastAuto",
                "calibrated direct stroke slot="+shape.slot+" fingers="+fingerX+","+fingerY)
            gesture(epoch,Phase.ALIGN) { service,done ->
                service.beginDrag(shape.pickX*scaleX(frame),shape.pickY*scaleY(frame),
                    fingerX*scaleX(frame),fingerY*scaleY(frame),app,
                    pace.firstTravelMs().toLong(),done)
            }
        } else {
            stagedDragCount++
            fingerX = frame.width*.5f
            fingerY = min(frame.height-frame.cell*.7f,
                frame.top+8*frame.cell+shape.piece.h*frame.cell*.5f+2*frame.cell)
            status("기본 안전 이동 · 블록 " + (shape.slot+1) + "번 · 콤보 " + scoreTracker.combo())
            gesture(epoch,Phase.PICKED) { service,done ->
                service.beginDrag(shape.pickX*scaleX(frame),shape.pickY*scaleY(frame),
                    fingerX*scaleX(frame),fingerY*scaleY(frame),app,
                    pace.firstTravelMs().toLong(),done)
            }
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
            gesture(epoch,Phase.PICKED) { service,done -> service.moveHeld(fingerX*scaleX(frame),fingerY*scaleY(frame),
                    true,pace.probeMs().toLong(),done) }
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
        // First try the all-centers-changed directed matcher. If it rejects
        // a moving piece solely because its enlarged sprite overlaps the
        // ORIGINAL selected tray thumbnail, the source-mask-aware matcher
        // provides a second STRONGLY verified reading of the SAME frame pair.
        // Unlike the previous retry-only path, this saves an unnecessary
        // reverse gesture whenever full-shape color, temporal displacement,
        // command direction and global uniqueness are already confirmed.
        // Neither PICKED matcher can release a piece at a destination.
        val directed=if(phase==Phase.PICKED)
            DragAlignment.locateMovingEvidence(image,beforeImage!!,probeImage!!,frame,shape,
                fingerX,fingerY,probeDX,probeDY)
            else null
        val sourceOverlapRecovery=if(phase==Phase.PICKED && directed==null)
            DragAlignment.locatePickedAfterRetryEvidence(image,beforeImage!!,probeImage!!,
                frame,shape,fingerX,fingerY,probeDX,probeDY)
            else null
        val pickupMotion=directed ?: sourceOverlapRecovery
        // When the bright/dark board preview and the real sprite stack in one
        // column, an ordinary whole-shape search may lose uniqueness. Only
        // staged vertical bars with a verified earlier pickup, correction
        // attempt, complete target preview AND held-finger material evidence
        // may use the target grid as a stable preview proxy.
        val stackedPreview=if(phase==Phase.ALIGN && targetMatch==null &&
                !directDragUsed && corrections>=1)
            DragAlignment.locateHeldBelowSnappedPreview(image,beforeImage!!,frame,
                shape,targetX,targetY,fingerX,fingerY)
            else null
        // v1.4.13: Do not scan a tall/wide part of the frame from
        // scratch on EVERY held image. Prior fully verified sprite pixels
        // plus the most recent commanded travel give a predicted origin.
        // Strict shape+novelty checks run inside 0.9 then 2.3-cell windows.
        // If both miss, the existing global search and all specialized
        // recoveries remain available (no trusting motion alone).
        val predictedOrigin=if(phase==Phase.ALIGN && targetMatch==null &&
                stackedPreview==null) {
            val trusted=trustedHeldOrigin
            when {
                trusted==null -> null
                lastCorrectionOrigin!=null && !lastCorrectionTrusted -> null
                lastCorrectionOrigin!=null -> {
                    val old=lastCorrectionOrigin!!
                    Pair(old.x+lastCorrectionStepX*dragResponse.gainX(),
                         old.y+lastCorrectionStepY*dragResponse.gainY())
                }
                else -> Pair(trusted.x,trusted.y)
            }
        } else null
        val roiTight=if(predictedOrigin!=null)
            DragAlignment.locatePredicted(image,beforeImage!!,frame,shape,
                predictedOrigin.first,predictedOrigin.second,.9f)
            else null
        val roiWide=if(predictedOrigin!=null && roiTight==null)
            DragAlignment.locatePredicted(image,beforeImage!!,frame,shape,
                predictedOrigin.first,predictedOrigin.second,2.3f)
            else null
        val regularHeld=if(phase==Phase.ALIGN && targetMatch==null &&
                stackedPreview==null) {
            val match=roiTight ?: roiWide ?: DragAlignment.locate(
                image,beforeImage!!,frame,shape,fingerX,fingerY)
            if(roiTight!=null) roiTightMatches++
            else if(roiWide!=null) roiWideMatches++
            else roiFullFallbacks++
            match
        } else null
        // Only an actual strict sprite matcher can renew prediction trust.
        // A snapped-grid proxy, partial/occluded sprite or gesture command
        // cannot seed this state; all such cases use the original fallback.
        if(targetMatch!=null) trustedHeldOrigin=targetMatch
        else if(regularHeld!=null) trustedHeldOrigin=regularHeld
        // Diagnostic (13)/(17): a moving colored piece can overlay ONE
        // existing SAME-COLOR occupied BOARD cell. Normal tracking first.
        // This fallback is correction-only, never sufficient to release.
        val occupiedBoardRecovery=if(phase==Phase.ALIGN && regularHeld==null &&
                targetMatch==null && stackedPreview==null &&
                !directDragUsed && corrections>=1)
            DragAlignment.locateOverOccupiedBoard(image,beforeImage!!,frame,
                shape,fingerX,fingerY)
            else null
        // Diag (14): only a three-line-clear preview from an exact red L,
        // including its distinctive bright face over the dim line-glow,
        // can recover an otherwise untrackable target. This is a release
        // proxy, never a physical sprite coordinate or calibration sample.
        val comboClearPreview=if(phase==Phase.ALIGN && targetMatch==null &&
                stackedPreview==null && regularHeld==null &&
                occupiedBoardRecovery==null && !directDragUsed && corrections>=1 &&
                dragResponse.measured())
            DragAlignment.locateComboClearPreview(image,beforeImage!!,frame,
                shape,targetX,targetY,fingerX,fingerY)
            else null
        // Diagnostic (15): one cleared BLUE row can visually merge with
        // the lower half of a genuine held 3x2 L, while its upper 3 squares
        // remain separately beveled above the row. This proxy is restricted
        // to already measured, staged ALIGN strokes and a uniquely legal
        // one-row clear; never infer its coordinates as motor response.
        val lineGlowPreview=if(phase==Phase.ALIGN && targetMatch==null &&
                stackedPreview==null && regularHeld==null &&
                occupiedBoardRecovery==null && comboClearPreview==null &&
                !directDragUsed && corrections>=1 && dragResponse.measured())
            DragAlignment.locateLineGlowRightL(image,beforeImage!!,frame,
                shape,targetX,targetY,fingerX,fingerY)
            else null
        // Diagnostic (18): a two-square BLUE diagonal completes one row.
        // The row-clear glow wipes out strict held-sprite edges. This highly
        // constrained target preview is release-only, never motor calibration.
        val diagonalLineClearPreview=if(phase==Phase.ALIGN &&
                targetMatch==null && stackedPreview==null &&
                regularHeld==null && occupiedBoardRecovery==null &&
                comboClearPreview==null && lineGlowPreview==null &&
                !directDragUsed && corrections>=1 && dragResponse.measured())
            DragAlignment.locateDiagonalLineClearPreview(image,beforeImage!!,
                frame,shape,targetX,targetY,fingerX,fingerY)
            else null
        // Diagnostic (16): orange four-cell zigzag may be fully visible
        // with dark bevel centers that fail the normal strict-color tracker.
        // Pixel-gradient and temporal novelty are separately checked.
        // This may ONLY steer an already held piece; it cannot release it.
        val bevelZigzagRecovery=if(phase==Phase.ALIGN && targetMatch==null &&
                stackedPreview==null && regularHeld==null &&
                occupiedBoardRecovery==null && comboClearPreview==null &&
                lineGlowPreview==null && diagonalLineClearPreview==null &&
                !directDragUsed && corrections>=1 &&
                dragResponse.measured())
            DragAlignment.locateBeveledOrangeZigzag(image,beforeImage!!,
                frame,shape,fingerX,fingerY)
            else null
        val found=if(phase==Phase.PICKED) pickupMotion?.latest
            else targetMatch ?: stackedPreview ?: regularHeld ?: occupiedBoardRecovery
                ?: comboClearPreview ?: lineGlowPreview ?: diagonalLineClearPreview
                ?: bevelZigzagRecovery
        if(bevelZigzagRecovery!=null) {
            usedBevelZigzagRecovery=true
            lastCorrectionOrigin=null
            pace.uncertainty()
            android.util.Log.i("BlockBlastAuto",
                "BEVEL_ZIGZAG_RECOVERY correction-only at "+
                bevelZigzagRecovery.x+","+bevelZigzagRecovery.y)
        }
        if(diagonalLineClearPreview!=null) {
            usedDiagonalLineClearPreview=true
            lastCorrectionOrigin=null // snapped preview is NOT sprite motion
            pace.uncertainty()
            android.util.Log.i("BlockBlastAuto",
                "DIAGONAL_LINE_CLEAR strict target preview confirmed; no motor calibration")
        }
        if(lineGlowPreview!=null) {
            usedLineGlowPreview=true
            lastCorrectionOrigin=null
            pace.uncertainty()
            android.util.Log.i("BlockBlastAuto",
                "LINE_GLOW_RIGHT_L strict preview+finger confirmed; no motor calibration")
        }
        if(comboClearPreview!=null) {
            usedComboClearPreview=true
            lastCorrectionOrigin=null // never learn response gain from grid proxy
            pace.uncertainty()
            android.util.Log.i("BlockBlastAuto",
                "COMBO_CLEAR_PREVIEW guarded release proxy target="+targetX+","+targetY)
        }
        if(occupiedBoardRecovery!=null) {
            usedBoardOverlapRecovery=true
            pace.uncertainty()
            lastCorrectionOrigin=null // no motor gain learning from this proxy
            lastBoardOverlapShape=shape.piece.key
            android.util.Log.i("BlockBlastAuto",
                "BOARD_OVERLAP_RECOVERY correction-only piece="+
                shape.piece.key+" origin="+
                occupiedBoardRecovery.x+","+occupiedBoardRecovery.y)
        }
        if(sourceOverlapRecovery!=null)
            android.util.Log.i("BlockBlastAuto","pickup confirmed with selected-tray source mask; "+
                "directed unique shape at "+sourceOverlapRecovery.latest.x+","+sourceOverlapRecovery.latest.y)
        // This evidence is based on a WHOLE moving shape and a deliberate
        // finger probe. Do not estimate gain from the thumbnail, guessed
        // pointer/sprite offset, or simply a successful Android callback.
        if(pickupMotion!=null && !dragResponse.measured()) {
            if(dragResponse.observe(probeDX,probeDY,
                    pickupMotion.movedX,pickupMotion.movedY,frame.cell))
                android.util.Log.i("BlockBlastAuto","verified probe motor gain "+
                    dragResponse.gainX()+","+dragResponse.gainY()+
                    " commanded="+probeDX+","+probeDY+
                    " sprite="+pickupMotion.movedX+","+pickupMotion.movedY)
        }
        if (!valid(epoch)) return
        if (found == null) {
            observed = null
            // A single probe may land during a render transition. Reprobe
            // once in the opposite direction without releasing the pointer.
            if (phase == Phase.PICKED && corrections == 0 && now-phaseAt > 900) {
                corrections = 1
                pace.uncertainty() // Uncertain initial motion reverts to baseline.
                probeImage = image
                probeDX = -frame.cell*.65f
                probeDY = 0f
                fingerX += probeDX
                status("잡기 재측정 · 블록 움직임 재확인")
                gesture(epoch,Phase.PICKED) { service,done ->
                    service.moveHeld(fingerX*scaleX(frame),fingerY*scaleY(frame),
                        true,pace.retryMs().toLong(),done)
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
        if (now-observedAt < pace.heldStableMs()) return
        if(phase==Phase.ALIGN) {
            // At most one gain update per completed correction gesture.
            // A subsequent full-shape observation is independent evidence
            // of how much this screen actually moved for that finger step.
            lastCorrectionOrigin?.let { from ->
                val actualX=found.x-from.x
                val actualY=found.y-from.y
                if(dragResponse.observe(lastCorrectionStepX,lastCorrectionStepY,
                        actualX,actualY,frame.cell))
                    android.util.Log.i("BlockBlastAuto",
                        "measured correction gain="+dragResponse.gainX()+","+
                        dragResponse.gainY()+" actual="+actualX+","+actualY)
                lastCorrectionOrigin=null
            }
        }
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
        // Grid-snapped previews may render the sprite origin ~0.2 cell
        // away from the legal target no matter how we move the pointer.
        // Only release once 1) the strict local visual tracker succeeds,
        // 2) its origin lies within the same target-cell basin,
        // 3) ALL 2x2 (or other shape) requested cell interiors are newly
        // occupied by the correct held material, and 4) that visual state
        // has remained stable across observations (checked above).
        // Recovery-only tracking is NEVER sufficient for a release.
        val targetColumn=kotlin.math.round((targetX-frame.left)/frame.cell).toInt()
        val targetRow=kotlin.math.round((targetY-frame.top)/frame.cell).toInt()
        val targetCellsMatch=phase==Phase.ALIGN && (targetMatch!=null ||
            stackedPreview!=null || comboClearPreview!=null || lineGlowPreview!=null ||
            diagonalLineClearPreview!=null) &&
            (lineGlowPreview!=null || DragAlignment.targetCellPreview(image,beforeImage!!,frame,shape,targetColumn,targetRow))
        val snappedToTarget=targetCellsMatch && abs(dx)<frame.cell*.32f &&
            abs(dy)<frame.cell*.32f
        if (phase == Phase.ALIGN && snappedToTarget) {
            // Capture a learning candidate, NOT a confirmed sample.
            // It is committed ONLY if MoveVerifier proves the expected
            // board and consumed tray slot after the pointer is released.
            // A stacked-preview proxy is NOT the genuine moving sprite
            // coordinate, so it must NEVER update finger-to-sprite calibration.
            if(stackedPreview!=null || usedBoardOverlapRecovery ||
                    comboClearPreview!=null || lineGlowPreview!=null ||
                    usedBevelZigzagRecovery) {
                pendingDragSample=null
                pace.uncertainty()
                android.util.Log.i("BlockBlastAuto",
                    "STACKED_PREVIEW strict target+finger material verified; no calibration")
            } else pendingDragSample=DragCalibration.Observation(shape.slot,shape.piece.key,
                shape.piece.w,shape.piece.h,frame.cell,
                fingerX,fingerY,found.x,found.y)
            status("목표 칸 미리보기 확인 · 블록 놓기")
            android.util.Log.i("BlockBlastAuto","ALIGN target cells confirmed, render offset="+dx+","+dy)
            gesture(epoch,Phase.VERIFY) { service,done -> service.moveHeld(fingerX*scaleX(frame),fingerY*scaleY(frame),
                false,pace.releaseMs().toLong(),done) }
        } else {
            // Damped short correction strokes avoid a large vertical
            // finger jump changing the game's lift offset mid-gesture.
            // The controller never releases without stable local evidence.
            if (corrections++ >= 10) {
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
            // The game may translate an input by 1.5x (real v1.4.1 case).
            // Divide by the measured visual response and prevent the sprite
            // from leaving the image before it can be reobserved.
            if(corrections>=4) pace.uncertainty()
            val step=try {
                dragResponse.correction(dx,dy,found.x,found.y,
                    shape.piece.w,shape.piece.h,frame.cell,frame.width,frame.height)
            } catch (e: IllegalArgumentException) {
                lastServoFailureDetail=e.message
                android.util.Log.w("BlockBlastAuto",
                    "servo rejected reason="+e.message+
                    " origin="+found.x+","+found.y+
                    " target="+targetX+","+targetY+
                    " delta="+dx+","+dy+
                    " sprite="+shape.piece.w+"x"+shape.piece.h+
                    " cell="+frame.cell+" screen="+frame.width+"x"+frame.height)
                freezeFailure(image,"UNSAFE_OR_CLIPPED_SERVO_GEOMETRY")
                halt("블록이 화면 경계에서 안전하게 추적되지 않아 정지 · 진단 파일 저장 가능")
                return
            }
            val stepX=step[0]; val stepY=step[1]
            if(abs(stepX)<.5f && abs(stepY)<.5f) {
                freezeFailure(image,"CORRECTION_BLOCKED_BY_CAPTURE_BOUNDARY")
                halt("화면 경계 때문에 안전한 보정 불가 · 자동 정지")
                return
            }
            lastCorrectionOrigin=found
            lastCorrectionTrusted=targetMatch!=null || regularHeld!=null
            lastCorrectionStepX=stepX
            lastCorrectionStepY=stepY
            fingerX += stepX; fingerY += stepY
            if (fingerX<2 || fingerX>frame.width-2 || fingerY<2 || fingerY>frame.height-2) { halt("터치 범위를 벗어나는 배치 · 정지"); return }
            android.util.Log.i("BlockBlastAuto","response-aware servo "+corrections+
                " directStart="+directDragUsed+" requested="+dx+","+dy+
                " spriteGain="+dragResponse.gainX()+","+dragResponse.gainY()+
                " commanded="+stepX+","+stepY+" actualFinger="+fingerX+","+fingerY)
            status("블록 위치 단계 보정 " + corrections)
            gesture(epoch,Phase.ALIGN) { service,done ->
                service.moveHeld(fingerX*scaleX(frame),fingerY*scaleY(frame),
                    true,pace.correctionMs().toLong(),done)
            }
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
            .put("fingerX",fingerX).put("fingerY",fingerY).put("probeDX",probeDX).put("probeDY",probeDY).put("targetX",targetX).put("targetY",targetY)
            .put("observedOriginX",observed?.x ?: JSONObject.NULL)
            .put("observedOriginY",observed?.y ?: JSONObject.NULL)
            .put("servoFailureDetail",lastServoFailureDetail ?: JSONObject.NULL)
            .put("usedBoardOverlapRecovery",usedBoardOverlapRecovery)
            .put("boardOverlapShape",lastBoardOverlapShape ?: JSONObject.NULL)
            .put("roiTightMatches",roiTightMatches)
            .put("roiWideMatches",roiWideMatches)
            .put("roiFullFallbacks",roiFullFallbacks)
            .put("lastCorrectionTrusted",lastCorrectionTrusted)
            .put("usedComboClearPreview",usedComboClearPreview)
            .put("usedLineGlowPreview",usedLineGlowPreview)
            .put("usedDiagonalLineClearPreview",usedDiagonalLineClearPreview)
            .put("usedBevelZigzagRecovery",usedBevelZigzagRecovery)
            .put("pickupRetry",phase==Phase.PICKED && corrections==1)
            .put("calibratedDirectStart",directDragUsed)
            .put("exactShapeCalibrationConfidence",dragCalibration.confidence(shape.slot,shape.piece.key))
            .put("slotAnyShapeCalibrationConfidence",dragCalibration.confidence(shape.slot))
            .put("visualResponseMeasured",dragResponse.measured())
            .put("gestureSpeedTier",pace.tier().name)
            .put("consecutiveCleanPlacements",pace.cleanPlacements())
            .put("fingerToSpriteGainX",dragResponse.gainX())
            .put("fingerToSpriteGainY",dragResponse.gainY())
            .put("verifiedCalibrationSamples",dragCalibration.confirmedSamples())
            .put("directDragsAttempted",directDragCount)
            .put("stagedDragsAttempted",stagedDragCount)
            .put("verifiedDirectDrags",verifiedDirectCount)
            .put("verifiedStagedDrags",verifiedStagedCount)
            .put("currentGestureCount",motionGestureCount)
            .put("currentMotionDurationMs",if(motionStartedAt>0)
                SystemClock.uptimeMillis()-motionStartedAt else 0L)
            .put("averageVerifiedGestureCount",
                if(verifiedMoves>0) verifiedGestureTotal/verifiedMoves.toFloat() else JSONObject.NULL)
            .put("averageVerifiedMotionMillis",
                if(verifiedMoves>0) verifiedMotionMillisTotal/verifiedMoves else JSONObject.NULL)
            .put("visibleMovingShapeX",if(phase==Phase.PICKED && probeImage!=null)
                DragAlignment.locatePickedAfterRetry(image,original,probeImage!!,
                    frame,shape,fingerX,fingerY,probeDX,probeDY)?.x ?: JSONObject.NULL else JSONObject.NULL)
            .put("visibleMovingShapeY",if(phase==Phase.PICKED && probeImage!=null)
                DragAlignment.locatePickedAfterRetry(image,original,probeImage!!,
                    frame,shape,fingerX,fingerY,probeDX,probeDY)?.y ?: JSONObject.NULL else JSONObject.NULL)
            .put("targetCellPreview",DragAlignment.targetCellPreview(image,original,frame,shape,
                kotlin.math.round((targetX-frame.left)/frame.cell).toInt(),
                kotlin.math.round((targetY-frame.top)/frame.cell).toInt()))
            .put("uptimeMillis",SystemClock.uptimeMillis())
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
        motionGestureCount++
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
                    phase = next; phaseAt = SystemClock.uptimeMillis()
                    nextReadAt = phaseAt+pace.postGestureReadMs()
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
