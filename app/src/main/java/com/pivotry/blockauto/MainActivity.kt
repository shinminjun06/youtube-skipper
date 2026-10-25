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
    private val diagnosticRequest = 2002
    private val main = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var selectedMode: TextView
    private fun selectMode(auto: Boolean) {
        getSharedPreferences("play_mode",MODE_PRIVATE).edit().putBoolean("auto",auto).apply()
        val capture=CaptureService.active
        if(capture!=null) capture.setAutomatic(auto)
        else AutoService.live?.setAutomatic(auto)
        selectedMode.text=if(auto) "현재 모드: 자동 플레이 (게임 화면에 직접 입력)" else "현재 모드: 하이라이트 추천 (자동 터치 없음)"
        Toast.makeText(this,if(auto) "자동 모드 선택됨 · 화면 공유와 접근성 연결 후 시작하세요"
            else "추천 모드 선택됨",Toast.LENGTH_SHORT).show()
    }
    private val refresh = object : Runnable {
        override fun run() {
            val service = CaptureService.active
            state.text = "접근성: " + (if (AutoService.live != null) "연결됨" else "설정 필요") + "\n" +
                (service?.statusMessage ?: "화면 공유를 시작하세요")
            val automatic=service?.isAutomatic() ?: getSharedPreferences("play_mode",MODE_PRIVATE).getBoolean("auto",false)
            selectedMode.text=if(automatic) "현재 모드: 자동 플레이 (게임 화면에 직접 입력)"
                else "현재 모드: 하이라이트 추천 (자동 터치 없음)"
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
        label("Block Blast AutoPlay",27f)
        label("v${BuildConfig.VERSION_NAME} · AUTO 버전 · 별도 설치 앱",14f)
        state = label("준비 중",16f).apply { setTextIsSelectable(true) }
        selectedMode=label("현재 모드: 확인 중",18f)
        button("자동 플레이 모드 선택") { selectMode(true) }
        button("하이라이트 추천 모드 선택") { selectMode(false) }
        label("위 버튼 또는 게임 위 플로팅 창에서 모드를 선택할 수 있습니다. 자동 모드를 선택한 경우에만 게임에 드래그를 보냅니다.",14f)
        label("콤보는 COMBO 표시가 있을 때만 인식하고, 평소에는 확인된 줄 삭제를 추적합니다.",14f)
        label("1. 접근성을 연결하세요. 추천 표시와, 자동 모드에서만 드래그 입력을 보내기 위해 사용합니다.")
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
        label("3. 모드를 선택한 뒤 블록 블라스트를 열고 게임 위 플로팅 창에서 ▶ 시작을 누르세요.")
        label("추천 모드에서는 초록색 표시를 보고 직접 놓으세요. 자동 모드에서만 앱이 블록을 드래그하며, 설치 결과를 검증합니다.",14f)
        label("자동 모드에서 ▶ 시작을 누르면 드래그 중 화면 인식을 방해하지 않도록 플로팅 창이 사라집니다. 멈추려면 Android 알림을 내려 Block Blast AutoPlay의 '종료'를 누르거나 이 앱의 정지를 누르세요. 중단하면 플로팅 창은 복원됩니다.",14f)
        button("정지") { CaptureService.active?.halt("사용자가 정지했습니다") }
        button("위치 측정 실패 진단 파일 저장") {
            val service = CaptureService.active
            if (service?.hasDiagnostics() != true) Toast.makeText(this,"위치 측정 실패 후, 화면 공유를 종료하기 전에 저장할 수 있습니다",Toast.LENGTH_LONG).show()
            else {
                service.halt("진단 저장 준비")
                @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_TITLE,"BlockBlastAuto-Diagnostics.zip")
                },diagnosticRequest)
            }
        }
        label("자동 모드 위치 추적 실패 시 진단 ZIP을 저장해 보내면 블록 오인식 원인을 확인할 수 있습니다.",12f)
        label("진단 ZIP에는 조작 직전과 실패 직전의 게임 화면 및 좌표가 포함됩니다. 저장 버튼을 눌렀을 때만 파일을 만들며 자동으로 전송하지 않습니다.",12f)
        button("화면 공유 종료") { stopService(Intent(this,CaptureService::class.java)) }
        label("게임 버전·시각 효과에 따라 인식이 달라질 수 있습니다. 실제 게임 고득점이나 무중단 플레이를 보장하지 않습니다.",12f)
    }
    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    @Deprecated("Activity callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == diagnosticRequest && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val service = CaptureService.active
            if (service == null) Toast.makeText(this,"화면 공유가 종료되어 진단 기록이 없습니다",Toast.LENGTH_LONG).show()
            else service.saveDiagnostics(uri) { error -> Toast.makeText(this,error ?: "진단 ZIP을 저장했습니다",Toast.LENGTH_LONG).show() }
        }
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this,CaptureService::class.java).apply { putExtra("resultCode",resultCode); putExtra("data",data) })
            Toast.makeText(this,"게임으로 이동해 플로팅 창의 ▶ 시작을 누르세요",Toast.LENGTH_LONG).show()
        }
    }
}
