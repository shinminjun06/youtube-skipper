package com.pivotry.blockauto

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity:Activity(){
 private val CAPTURE=2001
 private lateinit var offsetLabel:TextView
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,36,32,24)}
  val scroll=ScrollView(this).apply{addView(root)}
  setContentView(scroll)
  fun label(text:String,size:Float=16f):TextView {
   val v=TextView(this).apply{this.text=text;textSize=size;setPadding(0,8,0,14)}
   root.addView(v);return v
  }
  label("Block Blast Auto v0.3",25f)
  label("첨부한 709×1536 화면을 기준으로 보드와 노란 블록을 인식하여 자동 배치합니다.")
  label("① 아래 버튼에서 접근성 서비스를 켜고, Block Blast Auto를 허용하세요.")
  root.addView(Button(this).apply{text="접근성 설정 열기";setOnClickListener{startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}})
  label("② 화면 공유를 허용하고 블록 블라스트 게임으로 돌아가세요.")
  root.addView(Button(this).apply{text="화면 공유 시작";setOnClickListener{
   val m=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
   @Suppress("DEPRECATION") startActivityForResult(m.createScreenCaptureIntent(),CAPTURE)
  }})
  label("③ 화면 우측 위의 플로팅 버튼에서 ▶ 자동 시작을 누르세요. 정지 버튼으로 언제든 멈출 수 있습니다.")
  label("드래그 세로 보정: 블록이 위로 날아가면 값을 높이고, 아래로 떨어지면 낮추세요. 기본 +115px.")
  val pref=getSharedPreferences("settings",MODE_PRIVATE)
  offsetLabel=label("",15f)
  val slider=SeekBar(this).apply{max=450;progress=(pref.getInt("dragYOffset",115)+100).coerceIn(0,450)}
  slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(b:SeekBar?,p:Int,fromUser:Boolean){
    offsetLabel.text="현재 Y 보정: "+(p-100)+" px (기준 해상도)"
    if(fromUser)pref.edit().putInt("dragYOffset",p-100).apply()
   }
   override fun onStartTrackingTouch(b:SeekBar?){}
   override fun onStopTrackingTouch(b:SeekBar?){}
  })
  root.addView(slider);offsetLabel.text="현재 Y 보정: "+(slider.progress-100)+" px"
  label("※ 현재 버전은 노란색 블록과 동일한 게임 UI를 가정하는 시험용 구현입니다. 화면 공유는 기기 내부에서만 처리하며 서버로 전송하지 않습니다.",13f)
  root.addView(Button(this).apply{text="화면 공유 종료";setOnClickListener{
   stopService(Intent(this@MainActivity,CaptureService::class.java))
  }})
 }
 @Deprecated("Activity callback")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==CAPTURE && resultCode==RESULT_OK && data!=null){
   val intent=Intent(this,CaptureService::class.java).apply{
    putExtra("resultCode",resultCode);putExtra("data",data)
   }
   if(Build.VERSION.SDK_INT>=26)startForegroundService(intent) else startService(intent)
   Toast.makeText(this,"게임으로 이동해 플로팅 버튼을 누르세요",Toast.LENGTH_LONG).show()
  }
 }
}