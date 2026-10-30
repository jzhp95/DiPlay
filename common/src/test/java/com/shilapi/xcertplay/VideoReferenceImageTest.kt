package com.shilapi.xcertplay

import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoReferenceImageTest {
    @Test fun pngPixelsMatchPublishedReferenceAndHashIsStable() {
        val bytes=VideoReferenceImage.bytes()
        val manifest=VideoReferenceImage.manifest(bytes)
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        try {
            assertEquals(960,bitmap.width);assertEquals(540,bitmap.height)
            assertEquals(Color.BLACK,bitmap.getPixel(60,130))
            assertEquals(Color.rgb(16,16,16),bitmap.getPixel(180,130))
            assertEquals(Color.RED,bitmap.getPixel(60,400))
            assertEquals(16,manifest.getJSONArray("patches").length())
            assertEquals(manifest.getString("sha256"),VideoReferenceImage.manifest(VideoReferenceImage.bytes()).getString("sha256"))
        } finally { bitmap.recycle() }
    }
}
