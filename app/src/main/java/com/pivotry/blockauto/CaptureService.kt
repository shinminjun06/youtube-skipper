package com.pivotry.blockauto

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import java.util.concurrent.Executors

class CaptureService:Service() {
 companion object { @Volatile var active:CaptureService?=null }
 private var projection:MediaProjection?=null
 private var display:VirtualDisplay?=null
 private var reader:ImageReader?=null
 private val main=Handler(Looper.getMainLooper())
 private val worker=Executors.newSingleThreadExecutor()
 @Volatile private var busy=false
 @Volatile private var running=false
 private var failures=0
 private var previousBoard:Long?=null
 private var previousShapeCount=-1
 private var awaiting=false
 private var dragDoneAt=0L
 private var lastStatus=""
 override fun onBind(intent:Intent?)=null
 override fun onCreate() {super.onCreate();active=this}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="STOP"){stopSelf();return START_NOT_STICKY}
  val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
  nm.createNotificationChannel(NotificationChannel("capture","게임 화면 분석",NotificationManager.IMPORTANCE_LOW))
  val notification=Notification.Builder(this,"capture")
   .setSmallIcon(android.R.drawable.ic_media_play)
   .setContentTitle("Block Blast Auto")
   .setContentText("화면 분석 준비 — 접근성 플로팅 버튼으로 시작")
   .setOngoing(true).build()
  if(Build.VERSION.SDK_INT>=29)startForeground(100,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
  else startForeground(100,notification)
  val result=intent?.getIntExtra("resultCode",Activity.RESULT_CANCELED)?:Activity.RESULT_CANCELED
  val data:Intent?=if(Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
  if(result!=Activity.RESULT_OK || data==null){stopSelf();return START_NOT_STICKY}
  val wm=getSystemService(WINDOW_SERVICE) as WindowManager
  val point=android.graphics.Point()
  @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(point)
  val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
  projection=manager.getMediaProjection(result,data)
  projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopSelf()}},main)
  reader=ImageReader.newInstance(point.x,point.y,PixelFormat.RGBA_8888,2)
  display=projection?.createVirtualDisplay("BlockBlastVision",point.x,point.y,resources.displayMetrics.densityDpi,
   DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader?.surface,null,main)
  status("화면 공유 준비됨 · 플로팅 버튼을 누르세요")
  main.postDelayed(loop,850)
  return START_NOT_STICKY
 }
 fun toggle() {
  running=!running;failures=0;awaiting=false;previousBoard=null
  status(if(running)"자동 플레이 시작" else "자동 플레이 정지")
  AutoService.live?.setRunning(running)
 }
 fun isRunning()=running
 private val loop=object:Runnable {
  override fun run() {
   if(running && !busy && SystemClock.elapsedRealtime()-dragDoneAt>800L)capture()
   main.postDelayed(this,650)
  }
 }
 private fun capture() {
  val img=reader?.acquireLatestImage()?:return
  busy=true
  worker.execute {
   try {
    val plane=img.planes[0]
    val full=Bitmap.createBitmap(plane.rowStride/plane.pixelStride,img.height,Bitmap.Config.ARGB_8888)
    full.copyPixelsFromBuffer(plane.buffer)
    val bitmap=Bitmap.createBitmap(full,0,0,img.width,img.height)
    val frame=Vision.read(bitmap)
    bitmap.recycle();full.recycle()
    if(frame==null){main.post {status("게임판 미검출 · 게임을 열어주세요")};return@execute}
    if(frame.shapes.isEmpty()){main.post {status("블록 대기 중 또는 게임 종료")};return@execute}
    if(awaiting) {
     if(frame.board==previousBoard && frame.shapes.size==previousShapeCount){
      failures++
      if(failures>=3){main.post {running=false;AutoService.live?.setRunning(false);status("드래그 반영 안 됨 · Y 보정 확인")};return@execute}
      main.post {status("블록 이동 확인 중 ("+failures+"/3)")}
      return@execute
     }
     awaiting=false;failures=0
    }
    val result=Solver.best(frame.board,frame.shapes.map {it.piece})
    if(result==null){main.post {status("블록 배치 불가 · 게임 종료 가능")};return@execute}
    val m=result.moves.first()
    val shape=frame.shapes[m.piece]
    val yOffset=getSharedPreferences("settings",MODE_PRIVATE).getInt("dragYOffset",0)
    val targetX=frame.left+(m.x+shape.piece.w*.5f)*frame.cell
    val targetY=frame.top+(m.y+shape.piece.h*.5f)*frame.cell+yOffset*frame.height/1536f
    previousBoard=frame.board;previousShapeCount=frame.shapes.size
    awaiting=true
    main.post {
     if(!running)return@post
     status("블록 "+(shape.slot+1)+" → ("+(m.x+1)+","+(m.y+1)+")")
     val service=AutoService.live
     if(service==null){running=false;status("접근성 서비스를 켜주세요")}
     else service.drag(shape.sourceX,shape.sourceY,targetX,targetY){success->
      dragDoneAt=SystemClock.elapsedRealtime()
      if(!success){failures++;awaiting=false;if(failures>=3){running=false;service.setRunning(false);status("접근성 드래그 실패")}}
     }
    }
   }catch(e:Exception){main.post{status("화면 분석 오류: "+e.javaClass.simpleName)}}
   finally{img.close();busy=false}
  }
 }
 private fun status(s:String){if(s!=lastStatus){lastStatus=s;AutoService.live?.setStatus(s)}}
 override fun onDestroy(){
  running=false;active=null
  main.removeCallbacksAndMessages(null)
  display?.release();reader?.close();projection?.stop()
  worker.shutdownNow()
  AutoService.live?.setRunning(false)
  super.onDestroy()
 }
}