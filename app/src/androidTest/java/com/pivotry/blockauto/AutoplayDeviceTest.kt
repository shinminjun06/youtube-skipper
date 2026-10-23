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

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AutoplayDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun await(message: String,timeout: Long=12000,condition: ()->Boolean) {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) { if(condition())return;Thread.sleep(80) }
        Assert.fail(message+" status="+CaptureService.active?.statusMessage)
    }
    @Before fun setup() { ui;await("accessibility service") { AutoService.live!=null } }
    @After fun cleanup() {
        instrumentation.runOnMainSync { CaptureService.active?.halt("테스트 종료");context.stopService(Intent(context,CaptureService::class.java));TouchFixtureActivity.live?.finish() }
        Thread.sleep(500)
    }
    @Test fun a_continuedStrokeKeepsPointerDownAndStopReturnsToTray() {
        instrumentation.startActivitySync(Intent(context,TouchFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(700)
        val activity=TouchFixtureActivity.live!!
        val screen=ui.takeScreenshot()!!
        val w=screen.width.toFloat();val h=screen.height.toFloat();screen.recycle()
        val done=CountDownLatch(1);var accepted=false
        instrumentation.runOnMainSync {
            AutoService.live!!.beginDrag(w*.21f,h*.2357f+w*.8858f+w*.8858f/8*2.4f,w*.5f,h*.88f,context.packageName) { accepted=it;done.countDown() }
        }
        Assert.assertTrue(done.await(8,TimeUnit.SECONDS));Assert.assertTrue(accepted)
        Assert.assertTrue("held across pickup and stage",activity.held)
        Assert.assertEquals("no premature ACTION_UP",0,activity.ups)
        Assert.assertEquals("one ACTION_DOWN across continued strokes",1,activity.downs)
        instrumentation.runOnMainSync { AutoService.live!!.abortHold() }
        await("stop releases pointer") { !activity.held }
        Assert.assertEquals("stop does not install a block",0,activity.placed)
    }
    @Test fun b_realCaptureControllerInstallsSixConsecutiveMoves() {
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
        instrumentation.startActivitySync(Intent(context,TouchFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(800)
        instrumentation.runOnMainSync { CaptureService.active!!.toggle() }
        await("six verified continuous placements",90000) { (CaptureService.active?.verifiedMoves ?: 0)>=6 }
        instrumentation.runOnMainSync { CaptureService.active!!.halt("테스트 정지") }
        Assert.assertTrue(TouchFixtureActivity.live!!.placed>=6)
        Assert.assertEquals("no dropped/cancelled block in fixture",0,TouchFixtureActivity.live!!.cancelled)
    }
}
