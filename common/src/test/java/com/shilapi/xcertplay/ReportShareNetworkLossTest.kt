package com.shilapi.xcertplay

import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.reportshare.ReportShareEndpoint
import com.shilapi.xcertplay.reportshare.ReportShareServer
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ReportShareNetworkLossTest.Addresses::class])
class ReportShareNetworkLossTest {
    @Test fun anExistingEndpointSurvivesAddressLossAndRecovery() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val server = ReportShareServer(ByteArray(0), { ByteArray(0) })
        val endpoint = ReportShareEndpoint(8888, "test-token")
        ReflectionHelpers.setField(activity, "reportShareServer", server)
        ReflectionHelpers.setField(activity, "reportShareEndpoint", endpoint)
        try {
            // The endpoint is already live when the final IPv4 address disappears.
            for (addresses in listOf(emptyList(), listOf("192.168.30.20"), emptyList())) {
                Addresses.current = addresses
                val content = LinearLayout(activity)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "reportShare",
                    ReflectionHelpers.ClassParameter(LinearLayout::class.java, content))
                val noNetwork = (0 until content.childCount).map(content::getChildAt)
                    .filterIsInstance<TextView>().any { it.text == activity.getString(R.string.settings_report_no_network) }
                assertEquals(addresses.isEmpty(), noNetwork)
                assertSame(endpoint, ReflectionHelpers.getField(activity, "reportShareEndpoint"))
            }
        } finally {
            Addresses.current = emptyList()
            server.stop()
            activity.finish()
        }
    }

    @Implements(value = ReportShareServer.Companion::class, isInAndroidSdk = false)
    class Addresses {
        @Implementation fun addressCandidates(preferredPrefix: String?): List<String> = current
        companion object { var current: List<String> = emptyList() }
    }
}
