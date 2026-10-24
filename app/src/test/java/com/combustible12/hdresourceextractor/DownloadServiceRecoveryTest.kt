package com.combustible12.hdresourceextractor

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DownloadServiceRecoveryTest {
    private class SchedulerDenied(base: Context): ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            if (name == Context.JOB_SCHEDULER_SERVICE) throw SecurityException("ACCESS_NETWORK_STATE required for jobs with a connectivity constraint")
            return super.getSystemService(name)
        }
    }
    @Test fun manifestMustDeclareNetworkState() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val nodes = doc.getElementsByTagName("uses-permission")
        assertTrue((0 until nodes.length).any { i ->
            nodes.item(i).attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", "name")?.nodeValue == "android.permission.ACCESS_NETWORK_STATE"
        })
    }
    @Test fun recoverySchedulerPermissionFailureDoesNotEscape() {
        val context = SchedulerDenied(RuntimeEnvironment.getApplication())
        assertFalse(DownloadService.schedule(context))
    }
    @Test fun schedulerFailureCannotBlockForegroundDownloadStart() {
        val app = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(app)
        while (shadow.nextStartedService != null) { }
        DownloadService.start(SchedulerDenied(app))
        assertEquals(DownloadService::class.java.name, shadow.nextStartedService?.component?.className)
    }
}
