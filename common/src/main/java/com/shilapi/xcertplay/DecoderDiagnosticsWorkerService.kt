package com.shilapi.xcertplay

import android.app.Service
import android.content.Intent
import android.os.*
import org.json.JSONObject
import java.io.File

/** All native codec/GPU and publication operations live in this expendable app-UID process. */
class DecoderDiagnosticsWorkerService : Service() {
    internal data class Ticket(val id: String, val run: String, val spec: JSONObject, val reply: Messenger)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val reply=message.replyTo ?: return@Handler true
        val data=message.data;val id=data.getString("request") ?: return@Handler true
        val run=data.getString("run") ?: return@Handler true
        if (!run.matches(Regex("[a-f0-9-]{36}"))) return@Handler true
        when(message.what) {
            HELLO -> send(reply,HELLO,id,run,JSONObject().put("pid",Process.myPid()))
            EXECUTE -> {
                if (ticket != null) { send(reply,RESULT,id,run,JSONObject().put("error","worker_busy"));return@Handler true }
                val spec=runCatching { JSONObject(data.getString("spec") ?: "{}") }.getOrNull() ?: return@Handler true
                val t=Ticket(id,run,spec,reply);ticket=t
                if (spec.optString("operation")=="case") {
                    startActivity(Intent(this,DecoderDiagnosticsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("request",id))
                } else Thread({
                    val result=runCatching {
                        val directory=DecoderDiagnosticsRun.directory(this,run)
                        when(spec.getString("operation")) {
                            "inventory" -> {
                                @Suppress("DEPRECATION") val packageInfo=packageManager.getPackageInfo(packageName,0)
                                val apkSha256=DecoderDiagnosticsRun.sha(File(applicationInfo.sourceDir))
                                DecoderDiagnosticsRun.json(File(directory,"device/app-identity.json"),JSONObject().put("package",packageName)
                                    .put("versionName",packageInfo.versionName).put("versionCode",packageInfo.longVersionCode)
                                    .put("uid",Process.myUid()).put("apkSha256",apkSha256))
                                DecoderDiagnosticsBundle.freezeReferences(this,directory)
                                val device=VideoAdaptationDevice.collect(this,spec.getJSONObject("screen")) { partial ->
                                    DecoderDiagnosticsRun.json(File(directory,"device/inventory.json"),partial)
                                }
                                device.put("appUid",Process.myUid()).put("apkSha256",apkSha256)
                                DecoderDiagnosticsRun.json(File(directory,"device/inventory.json"),device)
                                DecoderDiagnosticsRun.json(File(directory,"device/inventory-fields.json"),DecoderDiagnosticsFields.describe(device))
                                JSONObject().put("inventoryComplete",true)
                            }
                            "source_index" -> {
                                val identity=DecoderDiagnosticsRun.read(File(directory,"device/app-identity.json"))
                                val plan=DecoderDiagnosticsRun.plan()
                                val names=(0 until plan.length()).map { plan.getJSONObject(it).getString("asset") }
                                val index=com.shilapi.xcertplay.diagnostics.DiagnosticSourceIndex(
                                    File(filesDir,"decoder-diagnostics-reference-cache"))
                                    .load(identity.getString("apkSha256"),names){assets.open("color-test/$it.mp4")}
                                DecoderDiagnosticsRun.json(File(directory,"reference/source-hashes.json"),index)
                                JSONObject().put("sourceHashes",index.getJSONObject("hashes"))
                                    .put("sourceHashApk",index.getString("apkSha256"))
                            }
                            "publish" -> {
                                val frozen=DecoderDiagnosticsRun.read(File(directory,"sealed-run.json"))
                                check(frozen.optString("id")==run)
                                val zip=DecoderDiagnosticsBundle.seal(this,frozen)
                                val saved=DiagnosticExportStore.saveFileWithoutPicker(this,zip,"application/zip",indexDownload=false)
                                JSONObject().put("publication",if(saved.savedInApp)"private_only" else "public_verified")
                                    .put("publicPath",saved.savedPath ?: zip.absolutePath).put("publicBytes",zip.length())
                                    .put("publicSha256",DecoderDiagnosticsRun.sha(zip)).put("publicUri",saved.uri.toString())
                                    .put("publicationError",saved.publicFailure ?: "").put("internalZip",zip.absolutePath)
                            }
                            else -> error("Unknown operation")
                        }
                    }.getOrElse { JSONObject().put("error","${it.javaClass.simpleName}: ${it.message}".take(300)) }
                    complete(t,result)
                },"diagnostic-operation").apply { isDaemon=true;start() }
            }
        }
        true
    })
    override fun onBind(intent: Intent?) = messenger.binder
    override fun onDestroy() { ticket=null;super.onDestroy() }
    companion object {
        const val HELLO=1;const val EXECUTE=2;const val RESULT=3
        @Volatile internal var ticket: Ticket?=null
        internal fun complete(t: Ticket,result: JSONObject) {
            if(ticket !== t)return
            ticket=null
            send(t.reply,RESULT,t.id,t.run,result)
        }
        private fun send(to:Messenger,what:Int,id:String,run:String,result:JSONObject) {
            runCatching { to.send(Message.obtain(null,what).apply { data=Bundle().apply {
                putString("request",id);putString("run",run);putString("result",result.toString())
            } }) }
        }
    }
}
