package com.ecarx.xui.adaptapi.input

import android.content.Context
import java.util.concurrent.CountDownLatch

/** Test-only API shape; no vendor implementation is packaged in the APK. */
interface IKeyCallback {
    fun onKeyPressed(code: Int): Boolean
    fun onKeyReleased(code: Int): Boolean
    fun onKeyShortPressed(code: Int): Boolean
    fun onKeyShortReleased(code: Int): Boolean
    fun onKeyLongPressed(code: Int): Boolean
    fun onKeyLongReleased(code: Int): Boolean
}
class KeyCode { companion object { @JvmField var KEYCODE_R_VOICE_ASSIST = 200231 } }
class Input {
    @Volatile var callback: IKeyCallback? = null
    @Volatile var released: IKeyCallback? = null
    @Volatile var codes: IntArray = intArrayOf()
    val registered = CountDownLatch(1)
    val abandoned = CountDownLatch(1)
    fun requestKeysInterception(keys: IntArray, listener: IKeyCallback): IntArray {
        codes = keys; callback = listener; registered.countDown(); return keys
    }
    fun abandonKeysInterception(listener: IKeyCallback) { released = listener; abandoned.countDown() }
    companion object {
        @JvmField var instance = Input()
        @JvmStatic fun create(context: Context): Input = instance
    }
}
