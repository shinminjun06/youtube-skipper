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

class AutoService:AccessibilityService() {
 companion object {@Volatile var live:AutoService?=null}
 private var floating:LinearLayout?=null
 private var toggleButton:Button?=null
 private var statusText:TextView?=null
 private lateinit var wm:WindowManager
 private val handler=Handler(Looper.getMainLooper())
 override fun onServiceConnected(){
  super.onServiceConnected();live=this
  wm=getSystemService(WINDOW_SERVICE) as WindowManager
  val layout=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setBackgroundColor(0xCC202020.toInt())
   setPadding(9,6,9,6)
  }
  val btn=Button(this).apply{
   text="▶ 자동 시작"
   setOnClickListener{
    val c=CaptureService.active
    if(c==null)setStatus("앱에서 화면 공유 먼저 켜기")
    else c.toggle()
   }
  }
  val status=TextView(this).apply{
   text="화면 공유 권한 필요"
   setTextColor(-1);textSize=10f
   maxWidth=(resources.displayMetrics.widthPixels*.42f).toInt()
  }
  layout.addView(btn);layout.addView(status)
  val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
   WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
   WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{
    gravity=Gravity.TOP or Gravity.END;y=(resources.displayMetrics.heightPixels*.11f).toInt()
  }
  wm.addView(layout,params);floating=layout;toggleButton=btn;statusText=status
 }
 fun setRunning(value:Boolean){handler.post {toggleButton?.text=if(value)"■ 자동 정지" else "▶ 자동 시작"}}
 fun setStatus(value:String){handler.post {statusText?.text=value}}
 fun drag(sx:Float,sy:Float,tx:Float,ty:Float,done:(Boolean)->Unit) {
  val path=Path().apply {moveTo(sx,sy);lineTo(tx,ty)}
  val gesture=GestureDescription.Builder()
   .addStroke(GestureDescription.StrokeDescription(path,0,720)).build()
  val ok=dispatchGesture(gesture,object:GestureResultCallback(){
   override fun onCompleted(gestureDescription:GestureDescription?){done(true)}
   override fun onCancelled(gestureDescription:GestureDescription?){done(false)}
  },handler)
  if(!ok)done(false)
 }
 override fun onAccessibilityEvent(event:AccessibilityEvent?){}
 override fun onInterrupt(){}
 override fun onDestroy(){
  live=null
  floating?.let {wm.removeView(it)}
  super.onDestroy()
 }
}