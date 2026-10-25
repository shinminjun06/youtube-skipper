package com.pivotry.blockauto

import android.app.UiAutomation
import android.content.Intent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.pivotry.blockauto.core.*

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AutoplayDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun await(message: String,timeout: Long=12000,condition: ()->Boolean) {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) { if(condition())return;Thread.sleep(80) }
        runCatching { shell("screencap -p /data/local/tmp/blockblast-failure.png") }
        Assert.fail(message+" status="+CaptureService.active?.statusMessage)
    }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { String(it.readBytes()) }
    @Before fun setup() {
        ui
        // UTP may reinstall/force-stop the target after a workflow enables it.
        shell("settings put secure enabled_accessibility_services com.pivotry.blockauto.autoplay/com.pivotry.blockauto.AutoService")
        shell("settings put secure accessibility_enabled 1")
        await("accessibility service: "+shell("settings get secure enabled_accessibility_services")) { AutoService.live!=null }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { CaptureService.active?.halt("테스트 종료");AutoService.live?.abortHold();context.stopService(Intent(context,CaptureService::class.java));TouchFixtureActivity.live?.finish() }
        Thread.sleep(500)
    }
    @Test fun a_continuedStrokeKeepsPointerDownAndStopReturnsToTray() {
        alignAtBoard()
        val activity=TouchFixtureActivity.live!!
        Assert.assertTrue("held across pickup and stage",activity.held)
        Assert.assertEquals("no premature ACTION_UP",0,activity.ups)
        Assert.assertEquals("one ACTION_DOWN across continued strokes",1,activity.downs)
        instrumentation.runOnMainSync { AutoService.live!!.abortHold() }
        await("stop releases pointer") { !activity.held }
        Assert.assertEquals("stop does not install a block",0,activity.placed)
    }
    @Test fun b_realCaptureControllerInstallsSixConsecutiveMoves() { continuousPlacements(false) }
    @Test fun f_darkPaletteInstallsSixConsecutiveMoves() { continuousPlacements(true) }
    @Test fun g_goldPlatedPurpleThemeInstallsSixConsecutiveMoves() { continuousPlacements(false,true) }
    @Test fun h_highLiftOverBoardInstallsSixMoves() { continuousPlacements(false,true,3.4f) }
    @Test fun i_veryHighLiftOverBoardInstallsSixMoves() { continuousPlacements(false,true,4.2f) }
    private fun startController(dark: Boolean,plated: Boolean,lift: Float,ignorePickup: Boolean = false,preview: Boolean = false): CaptureService {
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {
            val views=ArrayList<View>()
            activity.findViewById<View>(android.R.id.content).findViewsWithText(views,"화면 공유 시작",View.FIND_VIEWS_WITH_TEXT)
            Assert.assertTrue(views.isNotEmpty());views.first().performClick()
        }
        await("capture consent dialog") {
            val root=ui.rootInActiveWindow ?: return@await false
            val candidates=root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/start_button")+
                root.findAccessibilityNodeInfosByViewId("android:id/button1")
            candidates.firstOrNull { it.isEnabled && (it.text?.toString()?.contains("Start",true)==true || it.viewIdResourceName?.endsWith("start_button")==true) }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        await("projection service") { CaptureService.active!=null }
        instrumentation.runOnMainSync { CaptureService.active!!.setAutomatic(true) }
        instrumentation.startActivitySync(Intent(context,TouchFixtureActivity::class.java).putExtra("dark",dark).putExtra("plated",plated).putExtra("lift",lift).putExtra("ignorePickup",ignorePickup).putExtra("preview",preview).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(800)
        instrumentation.runOnMainSync { CaptureService.active!!.toggle() }
        return CaptureService.active!!
    }
    @Test fun k_stagingPreviewDoesNotStealHeldTracking() { continuousPlacements(false,true,3.4f,true) }
    private fun continuousPlacements(dark: Boolean,plated: Boolean = false,lift: Float = 1.85f,preview: Boolean = false) {
        startController(dark,plated,lift,preview=preview)
        await("six verified continuous placements",90000) { (CaptureService.active?.verifiedMoves ?: 0)>=6 }
        Assert.assertTrue(TouchFixtureActivity.live!!.placed>=6)
        Assert.assertEquals("no dropped/cancelled block in fixture",0,TouchFixtureActivity.live!!.cancelled)
        instrumentation.runOnMainSync { CaptureService.active!!.halt("테스트 정지") }
        await("stop after continuous play releases any next pickup") { !TouchFixtureActivity.live!!.held }
    }
    @Test fun j_failedPickupStopsAndExportsFrozenDiagnosticFrames() {
        val service=startController(false,true,3.4f,true)
        await("ignored pickup stops with diagnostics",16000) { !service.isRunning()&&service.hasDiagnostics() }
        Assert.assertEquals("no blind drop on failed pickup",0,TouchFixtureActivity.live!!.placed)
        await("cancel clears visible attempt marker") { !TouchFixtureActivity.live!!.attempted }
        val file=java.io.File(context.cacheDir,"held-failure-test.zip")
        val done=CountDownLatch(1);var error: String?="not called"
        instrumentation.runOnMainSync { service.saveDiagnostics(android.net.Uri.fromFile(file)) { error=it;done.countDown() } }
        Assert.assertTrue(done.await(10,TimeUnit.SECONDS));Assert.assertNull(error)
        java.util.zip.ZipFile(file).use { zip ->
            val data=zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().use { it.readText() }
            val meta=org.json.JSONObject(data);Assert.assertEquals("PICKED",meta.getString("phase"))
            for(name in arrayOf("before.png","held.png","probe.png")) {
                val bitmap=zip.getInputStream(zip.getEntry(name)).use { android.graphics.BitmapFactory.decodeStream(it) }
                Assert.assertNotNull(bitmap);Assert.assertEquals(720,bitmap.width);Assert.assertEquals(1560,bitmap.height)
                if(name!="before.png")Assert.assertEquals("frozen failure retains marker from before cancellation",0xff00ff88.toInt(),bitmap.getPixel(24,132))
                else Assert.assertNotEquals("pre-action image has no attempt marker",0xff00ff88.toInt(),bitmap.getPixel(24,132))
                bitmap.recycle()
            }
        }
        file.delete()
    }
    private fun screenshot(): Game.Image {
        val bitmap=ui.takeScreenshot()!!
        val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val result=Game.Image(bitmap.width,bitmap.height,pixels);bitmap.recycle();return result
    }
    private fun gesture(action: ((Boolean)->Unit)->Unit) {
        val done=CountDownLatch(1);var ok=false
        instrumentation.runOnMainSync { action { ok=it;done.countDown() } }
        Assert.assertTrue(done.await(8,TimeUnit.SECONDS));Assert.assertTrue(ok)
    }
    private fun alignAtBoard(): Pair<Float,Float> {
        instrumentation.startActivitySync(Intent(context,TouchFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(600)
        val before=screenshot();val frame=Recognizer.read(before)!!;val shape=frame.shapes[0]
        val fx=frame.width*.5f;val fy=frame.height*.88f
        gesture { done -> AutoService.live!!.beginDrag(shape.pickX,shape.pickY,fx,fy,context.packageName,done) }
        Thread.sleep(120)
        val origin=DragAlignment.locate(screenshot(),before,frame,shape,fx,fy)
        Assert.assertNotNull("held image measured through real Android rendering",origin)
        val tx=fx+frame.left-origin!!.x;val ty=fy+frame.top-origin.y
        gesture { done -> AutoService.live!!.moveHeld(tx,ty,true,400,done) }
        return tx to ty
    }
    @Test fun c_stopDuringTerminatingStrokeDoesNotPlace() {
        val target=alignAtBoard();val fixture=TouchFixtureActivity.live!!
        instrumentation.runOnMainSync {
            AutoService.live!!.moveHeld(target.first,target.second,false,600) { }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ AutoService.live!!.abortHold() },80)
        }
        await("stop during final release") { !fixture.held }
        Assert.assertEquals("stop must cancel pending UP over board",0,fixture.placed)
    }
    @Test fun d_appSwitchDuringReleaseDoesNotPlaceOrTouchNewApp() {
        val target=alignAtBoard();val fixture=TouchFixtureActivity.live!!
        instrumentation.runOnMainSync { AutoService.live!!.moveHeld(target.first,target.second,false,600) { } }
        Thread.sleep(80)
        ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        instrumentation.runOnMainSync { AutoService.live!!.abortHold() }
        await("window switch cancels pointer") { !fixture.held }
        Assert.assertEquals(0,fixture.placed)
        var accepted=true
        instrumentation.runOnMainSync { AutoService.live!!.beginDrag(100f,100f,200f,200f,context.packageName) { accepted=it } }
        Assert.assertFalse("foreground lock blocks new-game touches on launcher",accepted)
    }
    @Test fun e_disablingAccessibilityReleasesHeldPointer() {
        alignAtBoard();val fixture=TouchFixtureActivity.live!!
        shell("settings put secure enabled_accessibility_services ''")
        await("service destruction releases pointer") { AutoService.live==null && !fixture.held }
        Assert.assertEquals("service disable must not install a held block",0,fixture.placed)
    }
}
