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
import android.widget.Toast

class AutoService: AccessibilityService() {
 companion object { var pending:List<Solver.Move>?=null }
 private var overlay:Button?=null
 private lateinit var wm:WindowManager
 private val handler=Handler(Looper.getMainLooper())
 override fun onServiceConnected() {
  wm=getSystemService(WINDOW_SERVICE) as WindowManager
  val btn=Button(this).apply { text="배치 실행";setOnClickListener { runMoves() } }
  val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.END;y=180 }
  wm.addView(btn,params);overlay=btn
 }
 private fun runMoves() {
  val moves=pending ?: run { Toast.makeText(this,"먼저 계산하세요",Toast.LENGTH_SHORT).show();return }
  val p=getSharedPreferences("coords",MODE_PRIVATE)
  val keys=listOf("boardX","boardY","cell","trayX0","trayX1","trayX2","trayY")
  if(keys.any { p.getInt(it,0)<=0 }) { Toast.makeText(this,"좌표를 설정하세요",Toast.LENGTH_LONG).show();return }
  overlay?.isEnabled=false
  fun next(i:Int) {
   if(i>=moves.size) { overlay?.isEnabled=true;pending=null;return }
   val m=moves[i]
   val sx=p.getInt("trayX${m.piece}",0).toFloat()
   val sy=p.getInt("trayY",0).toFloat()
   val tx=(p.getInt("boardX",0)+m.x*p.getInt("cell",0)).toFloat()
   val ty=(p.getInt("boardY",0)+m.y*p.getInt("cell",0)).toFloat()
   val path=Path().apply { moveTo(sx,sy);lineTo(tx,ty) }
   val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,650)).build()
   val ok=dispatchGesture(gesture,object:GestureResultCallback() {
    override fun onCompleted(gestureDescription:GestureDescription?) { handler.postDelayed({next(i+1)},600) }
    override fun onCancelled(gestureDescription:GestureDescription?) { overlay?.isEnabled=true }
   },handler)
   if(!ok) overlay?.isEnabled=true
  }
  next(0)
 }
 override fun onAccessibilityEvent(event:AccessibilityEvent?) {}
 override fun onInterrupt() {}
 override fun onDestroy() { overlay?.let {wm.removeView(it)};super.onDestroy() }
}