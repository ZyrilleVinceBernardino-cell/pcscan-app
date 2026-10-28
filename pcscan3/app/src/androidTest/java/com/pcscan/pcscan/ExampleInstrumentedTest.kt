package com.pcscan.pcscan

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.pcscan.pcscan.ui.dashboard.FirestoreRecommendationRepository

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.pcscan.pcscan", appContext.packageName)
    }

    @Test
    fun cpuDisplayCleanupPreservesModelSuffixes() {
        val clean = displayCleaner("cleanCpuDisplayName")
        listOf("AMD Ryzen 7 7800X3D", "Intel Core i5-14600KF").forEach { model ->
            assertEquals(model, clean(model))
            listOf("-", "--", "\u2013", "\u2014").forEach { separator ->
                assertEquals(model, clean("$model $separator cores and threads"))
            }
        }
    }

    @Test
    fun gpuDisplayCleanupPreservesBrandAndModelSuffixes() {
        val clean = displayCleaner("cleanGpuDisplayName")
        listOf("ZOTAC GAMING GeForce RTX 4060 Ti", "AMD Radeon RX 9070 XT").forEach { model ->
            assertEquals(model, clean(model))
            listOf("-", "--", "\u2013", "\u2014").forEach { separator ->
                assertEquals(model, clean("$model $separator memory bus"))
            }
        }
    }

    private fun displayCleaner(methodName: String): (String) -> String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FirebaseApp.initializeApp(context)
        val repository = FirestoreRecommendationRepository(FirebaseFirestore.getInstance())
        // Exercise production cleanup with Android's regex engine without making network calls.
        val method = FirestoreRecommendationRepository::class.java
            .getDeclaredMethod(methodName, String::class.java)
            .apply { isAccessible = true }
        return { input -> method.invoke(repository, input) as String }
    }
}
