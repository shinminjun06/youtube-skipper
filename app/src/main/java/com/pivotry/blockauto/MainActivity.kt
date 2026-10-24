package com.pivotry.blockauto

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private val captureRequest = 2001
    private val main = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private val refresh = object : Runnable {
        override fun run() {
            val service = CaptureService.active
            state.text = "접근성: " + (if (AutoService.live != null) "연결됨" else "설정 필요") + "\n" +
                (service?.statusMessage ?: "화면 공유를 시작하세요")
            main.postDelayed(this,500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24*dp).toInt(),(24*dp).toInt(),(24*dp).toInt(),(32*dp).toInt())
        }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String,size: Float = 16f): TextView = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0,(8*dp).toInt(),0,(14*dp).toInt()); root.addView(this)
        }
        fun button(text: String,action: ()->Unit) { root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } }) }
        label("Block Blast Advisor",27f)
        label("${BuildConfig.VERSION_NAME} · 고득점·생존 추천",14f)
        state = label("준비 중",16f).apply { setTextIsSelectable(true) }
        label("고득점 추천 모드 · 현재 게임 화면에 표시된 콤보 숫자를 입력하세요. 예: COMBO 100이면 100. 콤보가 없다면 0.",14f)
        val comboInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "현재 표시 콤보 (기본 0)"
            setText(getSharedPreferences("score",MODE_PRIVATE).getInt("display_combo",0).toString())
        }
        root.addView(comboInput)
        button("현재 콤보 적용") {
            val visible = comboInput.text.toString().toIntOrNull()?.coerceIn(0,99999)
            if(visible==null) Toast.makeText(this,"숫자로 입력하세요",Toast.LENGTH_SHORT).show()
            else {
                getSharedPreferences("score",MODE_PRIVATE).edit().putInt("display_combo",visible).apply()
                CaptureService.active?.setDisplayedCombo(visible)
                Toast.makeText(this,"콤보 "+visible+" 기준으로 점수 최적화",Toast.LENGTH_SHORT).show()
            }
        }
        label("1. 접근성을 연결하세요. 게임 위에 배치 위치를 표시하기 위해 사용합니다. 직접 게임을 조작하지 않습니다.")
        button("접근성 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        label("2. 전체 화면 공유를 허용하세요. 화면은 기기 안에서만 분석하며 외부로 보내지 않습니다.")
        button("화면 공유 시작") {
            if (CaptureService.active != null) { Toast.makeText(this,"이미 공유 중입니다. 게임으로 돌아가세요",Toast.LENGTH_SHORT).show() }
            else {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val capture = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
                @Suppress("DEPRECATION") startActivityForResult(capture,captureRequest)
            }
        }
        label("3. 블록 블라스트의 세로 게임 화면을 열고, 우측 위 ▶ 추천 시작을 누르세요.")
        label("초록색 테두리로 표시된 블록을 직접 놓으세요. 앱은 게임을 자동 터치하지 않습니다.",14f)
        label("추천 정지 버튼을 누르면 표시가 사라집니다. 화면을 회전했거나 공유가 종료되면 다시 공유하세요.",14f)
        button("추천 정지") { CaptureService.active?.halt("사용자가 정지했습니다") }
        button("화면 공유 종료") { stopService(Intent(this,CaptureService::class.java)) }
        label("게임 버전·시각 효과에 따라 인식이 달라질 수 있습니다. 실제 게임 고득점이나 무중단 플레이를 보장하지 않습니다.",12f)
    }
    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    @Deprecated("Activity callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this,CaptureService::class.java).apply { putExtra("resultCode",resultCode); putExtra("data",data) })
            Toast.makeText(this,"게임으로 이동해 ▶ 추천 시작을 누르세요",Toast.LENGTH_LONG).show()
        }
    }
}
