package com.shilapi.xcertplay.session

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.DiPlaySessionService

/** A screen owns this binding; the service owns the runtime and native resources. */
internal class SessionClient(context:Context,private val connected:(SessionRuntime)->Unit):java.io.Closeable {
    private val app=context.applicationContext
    private var bound=false
    private var closed=false
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            if(closed)return
            val owner=(binder as? DiPlaySessionService.LocalBinder)?.runtime ?: return
            connected(owner)
        }
        override fun onServiceDisconnected(name:ComponentName) {}
    }
    fun connect() {
        if(closed || bound)return
        ContextCompat.startForegroundService(app,Intent(app,DiPlaySessionService::class.java))
        bound=app.bindService(Intent(app,DiPlaySessionService::class.java),connection,Context.BIND_AUTO_CREATE)
        check(bound){"session_service_bind_failed"}
    }
    override fun close(){closed=true;if(bound){app.unbindService(connection);bound=false}}
}
