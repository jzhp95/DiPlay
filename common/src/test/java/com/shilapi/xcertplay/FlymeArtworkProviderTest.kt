package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.net.Uri
import com.shilapi.flymeeas.FlymeArtworkProvider
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlymeArtworkProviderTest {
    @Test fun coverIsReadableButOtherFilesAndWriteModesAreRejected() {
        val context = RuntimeEnvironment.getApplication()
        java.io.File(context.cacheDir, "flyme-media-artwork").deleteRecursively()
        val bytes = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        }.toByteArray()
        val publish = FlymeArtworkProvider::class.java.getDeclaredMethod("publish", Context::class.java, ByteArray::class.java).apply { isAccessible = true }
        val uri = publish.invoke(null, context, bytes) as Uri
        assertEquals(uri, publish.invoke(null, context, bytes))
        val provider = FlymeArtworkProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = context.packageName + ".flyme-artwork" })
        provider.openFile(uri, "r").use { assertTrue(it!!.statSize > 0) }
        val forbidden = listOf(
            uri to "w", uri to "rw",
            Uri.parse("content://${context.packageName}.flyme-artwork/../secret") to "r",
            Uri.parse("content://${context.packageName}.flyme-artwork/private.xml") to "r",
            Uri.parse("content://another.app/${uri.lastPathSegment}") to "r",
        )
        for ((path, mode) in forbidden) {
            assertThrows(FileNotFoundException::class.java) { provider.openFile(path, mode) }
        }
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            publish.invoke(null, context, "not an image".toByteArray())
        }
    }
}
