package com.shilapi.xcertplay

import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.backup.SettingsBackupEndpoint
import com.shilapi.xcertplay.backup.SettingsBackupServer
import com.shilapi.xcertplay.backup.SettingsImportOutcome
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
@Config(sdk = [29], shadows = [BackupNetworkLossTest.Addresses::class])
class BackupNetworkLossTest {
    @Test fun anExistingEndpointSurvivesAddressLossAndRecovery() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val server = SettingsBackupServer(ByteArray(0), { ByteArray(0) }, { SettingsImportOutcome(0, 0, false) })
        val endpoint = SettingsBackupEndpoint(8888, "test-token")
        ReflectionHelpers.setField(activity, "backupServer", server)
        ReflectionHelpers.setField(activity, "backupEndpoint", endpoint)
        try {
            // The endpoint is already live when the final IPv4 address disappears.
            for (addresses in listOf(emptyList(), listOf("192.168.30.20"), emptyList())) {
                Addresses.current = addresses
                val content = LinearLayout(activity)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "backup",
                    ReflectionHelpers.ClassParameter(LinearLayout::class.java, content))
                val noNetwork = (0 until content.childCount).map(content::getChildAt)
                    .filterIsInstance<TextView>().any { it.text == activity.getString(R.string.backup_no_network) }
                assertEquals(addresses.isEmpty(), noNetwork)
                assertSame(endpoint, ReflectionHelpers.getField(activity, "backupEndpoint"))
            }
        } finally {
            Addresses.current = emptyList()
            server.stop()
            activity.finish()
        }
    }

    @Implements(value = SettingsBackupServer.Companion::class, isInAndroidSdk = false)
    class Addresses {
        @Implementation fun addressCandidates(preferredPrefix: String?): List<String> = current
        companion object { var current: List<String> = emptyList() }
    }
}
