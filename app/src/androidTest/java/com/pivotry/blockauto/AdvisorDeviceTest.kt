package com.pivotry.blockauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdvisorDeviceTest {
    @Test fun noGameplayGestureInjection() {
        val methods=AutoService::class.java.declaredMethods.map { it.name }.toSet()
        for(name in arrayOf("beginDrag","moveHeld","send","abortHold")) {
            assertFalse("advisor must never inject game gestures: $name",methods.contains(name))
        }
    }
}
