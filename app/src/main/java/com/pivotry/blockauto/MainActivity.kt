package com.pivotry.blockauto

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity:Activity() {
 private val occupied=BooleanArray(64)
 private val inputs=ArrayList<EditText>()
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16) }
  val scroll=ScrollView(this).apply {addView(root)}
  setContentView(scroll)
  fun label(s:String)=TextView(this).apply {text=s;textSize=16f;setPadding(0,8,0,8);root.addView(this)}
  label("Block Blast Auto v0.1 - 수동 보드 입력")
  label("채워진 칸을 누르세요.")
  val grid=GridLayout(this).apply {columnCount=8;rowCount=8};root.addView(grid)
  val width=(resources.displayMetrics.widthPixels-48)/8
  for(i in 0 until 64) {
   val v=TextView(this).apply {
    gravity=Gravity.CENTER
    setBackgroundColor(Color.LTGRAY)
    layoutParams=GridLayout.LayoutParams().apply {this.width=width-3;height=width-3;setMargins(1,1,1,1)}
    setOnClickListener {
     occupied[i]=!occupied[i]
     setBackgroundColor(if(occupied[i]) Color.BLUE else Color.LTGRAY)
    }
   }
   grid.addView(v)
  }
  label("블록 모양: # 채운 칸, . 빈칸, / 줄바꿈")
  for(s in listOf("##/##","###","#./##")) {
   val edit=EditText(this).apply {setSingleLine(true);setText(s)}
   inputs.add(edit);root.addView(edit)
  }
  val info=labelResult(root)
  root.addView(Button(this).apply {text="최적 배치 계산";setOnClickListener {
   try {
    val ps=inputs.map {Solver.parse(it.text.toString())}
    var b=0L
    for(i in 0..63) if(occupied[i]) b=b or (1L shl i)
    val result=Solver.best(b,ps)
    if(result==null) {info.text="세 블록을 모두 놓을 수 없습니다";AutoService.pending=null}
    else {
     info.text=result.moves.mapIndexed {j,m -> "${j+1}. 블록 ${m.piece+1}: 열 ${m.x}, 행 ${m.y}"}.joinToString("\n")
     AutoService.pending=result.moves
    }
   } catch(e:Exception) {info.text="입력 오류: ${e.message}"}
  }})
  label("자동 드래그 실험 기능: 화면 좌표를 직접 설정해야 합니다.")
  val coords=listOf("boardX","boardY","cell","trayX0","trayX1","trayX2","trayY")
  val prefs=getSharedPreferences("coords",MODE_PRIVATE)
  val edits=coords.map {key ->
   val e=EditText(this).apply {hint=key;inputType=2;setText(prefs.getInt(key,0).let{if(it==0) "" else it.toString()})}
   root.addView(e);e
  }
  root.addView(Button(this).apply {text="좌표 저장";setOnClickListener {
   prefs.edit().apply { coords.forEachIndexed {i,k -> putInt(k,edits[i].text.toString().toIntOrNull()?:0)} }.apply()
   Toast.makeText(this@MainActivity,"저장됨",Toast.LENGTH_SHORT).show()
  }})
  root.addView(Button(this).apply {text="접근성 서비스 설정 열기";setOnClickListener {startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}})
 }
 private fun labelResult(root:LinearLayout)=TextView(this).apply {textSize=17f;root.addView(this)}
}