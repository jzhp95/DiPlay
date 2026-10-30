package com.shilapi.xcertplay

import android.app.*
import android.content.*
import android.os.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.airplay.VideoCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The main-process supervisor never calls a codec, EGL or Downloads binder on its handler. */
class DecoderDiagnosticsService : Service() {
    private val main=Handler(Looper.getMainLooper())
    private val coordinator by lazy { DecoderDiagnosticsRun.coordinator(this) }
    private val run get() = coordinator.run
    private var commandAccepted = false
    private var destroyed = false
    private var startGeneration = 0
    private var diagnosticLease: com.shilapi.xcertplay.diagnostics.DiagnosticLease.Ticket? = null
    private var sourceHashes = JSONObject()
    private var replaySource = JSONObject()
    private var worker:Messenger?=null
    private var workerPid=0
    private var bound=false
    @Volatile private var request=""
    private var timeout:Runnable?=null
    private var continuation:((JSONObject)->Unit)?=null
    private var afterConnect:(()->Unit)?=null
    @Volatile private var closing=false
    private var suspendedSink:AndroidMediaSink?=null
    private var sourceSink:AndroidMediaSink?=null
    private var tap:VideoDiagnosticCapture?=null
    private var liveSession:com.shilapi.xcertplay.diagnostics.DiagnosticLease.Production?=null
    private var liveBefore:LiveVideoCounters?=null
    private var original=JSONObject()
    private val reply=Messenger(Handler(Looper.getMainLooper()){message->
        if(message.data.getString("run")!=run.optString("id") || message.data.getString("request")!=request)return@Handler true
        val value=runCatching{JSONObject(message.data.getString("result")?:"{}")} .getOrDefault(JSONObject().put("error","invalid_worker_reply"))
        if(message.what==DecoderDiagnosticsWorkerService.HELLO){
            val pid=value.optInt("pid")
            if(pid<=0 || pid==Process.myPid()){finishRun("invalid_worker_identity");return@Handler true}
            workerPid=pid
        }
        timeout?.let(main::removeCallbacks);timeout=null;request=""
        val callback=continuation;continuation=null;callback?.invoke(value);true
    })
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder){
            worker=Messenger(binder)
            send(DecoderDiagnosticsWorkerService.HELLO,JSONObject(),5000){value->
                if(value.has("error")){if(closing)publicationFailed("worker_handshake_failed")else finishRun("worker_handshake_failed")}
                else {val next=afterConnect;afterConnect=null;next?.invoke()}
            }
        }
        override fun onServiceDisconnected(name:ComponentName){
            worker=null;workerPid=0
            if(!closing && run.optString("status") in DecoderDiagnosticsRun.busyStates)finishRun("diagnostic_process_exited")
        }
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate();alive=true;foreground()
        coordinator.onStorageFailure = { reason -> storageUnavailable(reason) }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if (intent?.action == STOP) {
            startGeneration++
            if (run.has("id") && commandAccepted) finishRun("user_cancelled") else stopSelf()
            return START_NOT_STICKY
        }
        if (commandAccepted) return START_NOT_STICKY
        commandAccepted = true
        val generation = ++startGeneration
        val retry = intent?.action == RETRY
        val screen = runCatching { JSONObject(intent?.getStringExtra("screen") ?: "{}") }.getOrDefault(JSONObject())
        coordinator.recover {
            if (destroyed || generation != startGeneration) return@recover
            if (coordinator.unavailable) { stopSelf(); return@recover }
            if (retry) {
                if (!run.has("id")) { stopSelf(); return@recover }
                coordinator.adopt(run)
                coordinator.read("sealed-run.json") { sealed ->
                    if (sealed.optString("id") == run.optString("id")) { closing=true; publish() }
                    else finishRun("supervisor_process_restarted")
                }
            } else if (run.optString("status") in DecoderDiagnosticsRun.busyStates) {
                coordinator.adopt(run)
                finishRun("supervisor_process_restarted")
            } else {
                val fresh=DecoderDiagnosticsRun.fresh(screen)
                val ticket=com.shilapi.xcertplay.diagnostics.DiagnosticLease.acquire(
                    fresh.getString("id"),DiPlaySessionService.liveRuntime?.currentConnection?.controller)
                if(ticket==null){stopSelf();return@recover}
                diagnosticLease=ticket
                fresh.put("leaseId",ticket.leaseId).put("sessionGeneration",ticket.sessionGeneration)
                coordinator.adopt(fresh)
                original=settings();run.put("originalSettings",original)
                checkpoint { preflight() }
                main.postDelayed({if(!closing && run.optString("status") in DecoderDiagnosticsRun.busyStates)finishRun("suite_deadline_15_minutes")},900_000)
            }
        }
        return START_NOT_STICKY
    }
    private fun foreground(){
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("decoder-diagnostics","解码自动诊断",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,902,Intent(this,WheelTestActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,903,Intent(this,DecoderDiagnosticsService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"decoder-diagnostics").setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("DiPlay 解码自动诊断").setContentText("独立执行与保存报告，可返回应用查看进度")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"停止并保存",stop).build()).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(904,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(904,notification)
    }
    private fun settings()=JSONObject().put("softwareDirect",AirPlayPersistence.loadSoftwareDirectVideoOutput(this))
        .put("softwareYuv",AirPlayPersistence.loadSoftwareYuvEnabled(this)).put("hardwareYuv",AirPlayPersistence.loadHardwareYuvEnabled(this))
        .put("direct",AirPlayPersistence.loadDirectVideoOutput(this)).put("smooth",AirPlayPersistence.loadSmoothVideo(this))
        .put("hevc",AirPlayPersistence.loadHevcEnabled(this)).put("mtk",AirPlayPersistence.loadMtkDecoderTuning(this))
        .put("lowLatency",AirPlayPersistence.loadLowLatencyDecoder(this))
        .put("picture",JSONObject().apply{CarPlayPicture.keys.forEach{key->put(key,CarPlayPicture.value(CarPlayPicture.preferences(this@DecoderDiagnosticsService),key))}})
        .put("pictureDiagnosticPolicy","worker_instances_are_neutral_unfiltered; daily_values_preserved")
    private fun checkpoint(next: () -> Unit = {}) { coordinator.checkpoint(next) }
    private fun stage(status: String, next: () -> Unit) { run.put("status",status); checkpoint(next) }
    private fun gap(reason:String){run.getJSONArray("gaps").put(reason);coordinator.publishMemory()}
    private fun preflight(){
        if(closing)return
        val runId=run.getString("id")
        background("preflight",10000,{
            val directory=DecoderDiagnosticsRun.directory(this,runId)
            check(directory.usableSpace>DecoderDiagnosticsRun.BUDGET*2){"internal_space_below_512MiB"}
            var delivery="public_writable";var reason=""
            try{
                check(!DiagnosticExportStore.needsStoragePermission(this)){"storage_permission_denied"}
                if(Build.VERSION.SDK_INT<=28){
                    val public=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Apple CarPlay").apply{check(isDirectory||mkdirs())}
                    check(public.usableSpace>DecoderDiagnosticsRun.BUDGET){"downloads_space_below_256MiB"}
                    val probe=File(public,".diplay-$runId.probe")
                    try{val bytes=UUID.randomUUID().toString().toByteArray();probe.writeBytes(bytes);check(probe.readBytes().contentEquals(bytes))}finally{probe.delete()}
                } else delivery="MediaStore_verified_at_publication"
            }catch(e:Exception){delivery="private_fallback";reason=e.message?:e.javaClass.simpleName}
            JSONObject().put("deliveryPreflight",delivery).put("deliveryPreflightReason",reason)
        }){value->
            if(value.has("error")){finishRun(value.optString("error"));return@background}
            value.keys().forEach{key->run.put(key,value.get(key))}
            if(value.optString("deliveryPreflight")=="private_fallback")gap("public_delivery_preflight_failed:${value.optString("deliveryPreflightReason")}")
            stage("inventory"){connect{inventory()}}
        }
    }
    private fun inventory(){
        if(closing)return
        send(DecoderDiagnosticsWorkerService.EXECUTE,JSONObject().put("operation","inventory").put("screen",run.getJSONObject("screen")),30000){value->
            if(value.has("error")){
                gap("inventory_incomplete:${value.optString("error")}")
                releaseWorker(kill=true)
                main.postDelayed({if(!closing)connect{loadInventory()}},300)
            } else loadInventory()
        }
    }
    private fun loadInventory() {
        if(closing)return
        coordinator.read("device/inventory.json") { device ->
            if(device.has("manufacturer"))coordinator.mirrorDevice(this,device) { prepareSources() }
            else {gap("inventory_metadata_missing");prepareSources()}
        }
    }
    private fun prepareSources() {
        if(closing)return
        send(DecoderDiagnosticsWorkerService.EXECUTE,JSONObject().put("operation","source_index"),15_000){value->
            val hashes=value.optJSONObject("sourceHashes")
            if(value.has("error") || hashes==null){finishRun("source_index_unavailable:${value.optString("error")}");return@send}
            sourceHashes=JSONObject(hashes.toString())
            run.put("sourceHashes",JSONObject(sourceHashes.toString())).put("sourceHashApk",value.optString("sourceHashApk"))
            captureLive()
        }
    }
    private fun captureLive(){
        if(closing)return
        stage("live_capture") { attachLive() }
    }
    private fun attachLive() {
        if(closing)return
        val originalSnapshot=JSONObject(original.toString())
        background("live_source_attach",5000,{
            val session=diagnosticLease?.let{com.shilapi.xcertplay.diagnostics.DiagnosticLease.production(it)}
            check(diagnosticLease?.let{com.shilapi.xcertplay.diagnostics.DiagnosticLease.owns(it,DiPlaySessionService.liveRuntime?.currentConnection?.controller)}==true){"diagnostic_session_replaced"}
            check(session?.sink?.diagnosticsVideoSuspended()!=true) { "production_video_waiting_for_prior_release" }
            liveSession=session;sourceSink=session?.sink
            val evidence=session?.sink?.videoColorEvidence()
            if(evidence==null)JSONObject().put("unavailable","no_current_carplay_video")
            else{
                val capture=VideoDiagnosticCapture(VideoCodec.valueOf(evidence.codec),evidence.csd)
                check(!closing) { "capture_cancelled" }
                val current=checkNotNull(session)
                tap=capture;current.sink.diagnosticCapture=capture
                if(closing){current.sink.diagnosticCapture=null;error("capture_cancelled")}
                liveBefore=current.sink.liveVideoCounters()
                current.sink.requestDiagnosticKeyFrame()
                VideoDiagnosticTrace.begin()
                JSONObject().put("width",checkNotNull(session).width).put("height",session.height).put("sessionIdentity",System.identityHashCode(checkNotNull(session).controller))
                    .put("initialSource",ColorProbeEvidence.capture(evidence,true,false,emptyList(),"live_production",originalSnapshot))
                    .put("negotiation",DisplayDiagnosticSnapshot.report(this))
            }
        }){value->
            if(value.has("error")){finishRun(value.optString("error"));return@background}
            run.put("live",value)
            if(value.has("unavailable")){gap("real_input_missing:no_current_carplay_video");isolateProduction();return@background}
            main.postDelayed({if(!closing)Thread({sourceSink?.requestDiagnosticKeyFrame()},"diagnostic-gop-boundary").apply{isDaemon=true;start()}},11_000)
            main.postDelayed({if(!closing)finishCapture()},15_000)
        }
    }
    private fun finishCapture(){
        if(closing)return
        val runId = run.getString("id")
        val live = JSONObject(run.getJSONObject("live").toString())
        background("live_source_seal",5000,{
            val sink=sourceSink;sink?.diagnosticCapture=null
            val captured=tap?.finish();tap=null
            val directory=DecoderDiagnosticsRun.directory(this,runId)
            val same=DiPlaySessionService.liveRuntime?.currentConnection?.controller===liveSession?.controller
            val trace=VideoDiagnosticTrace.end()
            live.put("sameSession",same).put("timing",DecoderDiagnosticsMetrics.trace(trace,File(directory,"traces/live-production.tsv")))
                .put("fullTouchLatency","not_measured").put("actualTheme","unknown").put("motionVerification","not_observed_by_content_analysis")
            val after=sink?.liveVideoCounters();val before=liveBefore
            if(after!=null && before!=null)live.put("receivedFrames",after.received-before.received).put("submittedFrames",after.rendered-before.rendered)
                .put("receivedBytes",after.receivedBytes-before.receivedBytes)
            if(captured==null || captured.frames.isEmpty())return@background JSONObject().put("strictReplay",false).put("reason","no_random_access_video_received").put("liveSnapshot",live)
            val source=JSONObject().put("codec",captured.codec.name).put("strictReplay",captured.strictReplay && same)
                .put("reason",if(same)captured.reason else "session_changed").put("configurationChanged",captured.configurationChanged)
                .put("width",live.getInt("width")).put("height",live.getInt("height"))
                .put("themeActual","unknown").put("themeBasis","no_verified_iPhone_theme_feedback")
                .put("transportContinuity","unknown; protocol_loss_counters_not_available_at_sink")
                .put("replayCadenceBasis",if(captured.frames.size>1)"recorded_receive_intervals_with_last_interval_estimated_loop_tail"else"controlled_33ms_loop_original_cadence_unknown")
                .put("replayScope","same_compressed_input_path_comparison; not_actual_iPhone_interaction_or_transport_latency")
            val inputs=File(directory,"inputs").apply{mkdirs()}
            File(inputs,"source.csd").writeBytes(captured.csd)
            var offset=0;val units=JSONArray()
            File(inputs,"source.bin").outputStream().use{out->captured.frames.forEachIndexed{i,f->
                units.put(JSONObject().put("frameId",f.receivedNs.toString()).put("sequence",i).put("offset",offset).put("bytes",f.bytes.size).put("receivedNs",f.receivedNs)
                    .put("senderNs",f.senderNs).put("arrivalNs",f.arrivalNs).put("randomAccess",f.key))
                out.write(f.bytes);offset+=f.bytes.size
            }}
            source.put("accessUnits",units).put("sha256",DecoderDiagnosticsRun.sha(File(inputs,"source.bin")))
                .put("csdSha256",DecoderDiagnosticsRun.sha(File(inputs,"source.csd")))
            if(captured.frames.size>1)source.put("lastIntervalNs",captured.frames.last().receivedNs-captured.frames[captured.frames.size-2].receivedNs)
            DecoderDiagnosticsRun.json(File(inputs,"source.json"),source)
            DecoderDiagnosticsRun.json(File(directory,"device/live-production.json"),live)
            source.put("liveSnapshot",live)
        }){value->
            if(value.has("error")){finishRun(value.optString("error"));return@background}
            (value.remove("liveSnapshot") as? JSONObject)?.let { run.put("live",it) }
            replaySource=JSONObject(value.toString())
            run.put("replayAvailable",value.optBoolean("strictReplay"))
            if(!value.optBoolean("strictReplay"))gap("strict_replay_unavailable:${value.optString("reason")}")
            gap("live_light_and_dark_theme_pair_unverified")
            gap("physical_panel_and_complete_touch_latency_not_measured")
            isolateProduction()
        }
    }
    private fun isolateProduction(){
        if(closing)return
        val ticket=checkNotNull(diagnosticLease)
        val runId=ticket.leaseId
        background("production_video_release",8000,{
            val session=diagnosticLease?.let{com.shilapi.xcertplay.diagnostics.DiagnosticLease.production(it)}
            check(com.shilapi.xcertplay.diagnostics.DiagnosticLease.owns(ticket,DiPlaySessionService.liveRuntime?.currentConnection?.controller)){"diagnostic_session_replaced"}
            val sink=session?.sink
            suspendedSink=sink
            if(sink!=null)check(sink.suspendVideoForDiagnostics(runId) { closing }){"production_video_release_unconfirmed"}
            JSONObject().put("isolated",true)
        }){value->
            if(value.has("error")){finishRun(value.optString("error"));return@background}
            val plan=DecoderDiagnosticsRun.plan()
            if(run.optBoolean("replayAvailable")){val replay=DecoderDiagnosticsRun.plan(true);for(i in 0 until replay.length())plan.put(replay.getJSONObject(i))}
            run.put("plan",plan).put("total",plan.length()).put("position",0)
            monitorProduction()
            nextCase()
        }
    }
    private fun monitorProduction(){
        main.postDelayed({
            if(!closing && run.optString("status") in setOf("standard","replay")){
                val current=diagnosticLease?.let{com.shilapi.xcertplay.diagnostics.DiagnosticLease.production(it)}?.sink
                if(current!==suspendedSink || current?.diagnosticsVideoSuspended()==false)finishRun("production_video_context_changed")
                else monitorProduction()
            }
        },1000)
    }
    private fun nextCase(){
        if(closing)return
        val plan=run.getJSONArray("plan");val position=run.getInt("position")
        if(position>=plan.length()){finishRun("");return}
        val spec=JSONObject(plan.getJSONObject(position).toString()).put("operation","case")
        val replay=spec.optBoolean("replay")
        val hd=spec.getString("asset").let{it.contains("1080")||it.contains("light")||it.contains("dark")}
        spec.put("width",if(replay)replaySource.getInt("width")else if(hd)1920 else 640)
            .put("height",if(replay)replaySource.getInt("height")else if(hd)1080 else 360)
        if(!replay){
            val hash=sourceHashes.optString(spec.getString("asset"))
            if(hash.isEmpty()){finishRun("source_hash_index_missing");return}
            spec.put("sourceHash",hash)
        }
        run.put("currentCase",spec.getString("caseId"))
        stage(if(replay)"replay"else"standard") {
            if (closing) return@stage
            send(DecoderDiagnosticsWorkerService.EXECUTE,spec,DecoderDiagnosticsRun.caseDeadlineMs(spec)){value->
                coordinator.read("cases/${spec.getString("caseId")}.json") { result ->
                    if (closing) return@read
                    if(!result.has("caseId")){
                        val failed=DecoderDiagnosticsRun.result(spec,if(value.optString("error").contains("deadline"))"timeout"else"failed",value.optString("error","worker_result_missing"))
                        run.getJSONArray("results").put(failed)
                    }else {
                        if(result.optString("caseId") != spec.getString("caseId")){finishRun("case_result_identity_mismatch");return@read}
                        val compact=JSONObject(result.toString());compact.remove("logs");compact.remove("sourceEvidence")
                        run.getJSONArray("results").put(compact)
                    }
                    run.put("position",position+1)
                    checkpoint {
                        if(value.optBoolean("windowInterrupted") || result.optString("reason")=="window_covered_or_closed")finishRun("diagnostic_window_interrupted")
                        else if(value.has("error") || !value.optBoolean("cleanupConfirmed"))finishRun(value.optString("error","diagnostic_release_unconfirmed"))
                        else nextCase()
                    }
                }
            }
        }
    }
    private fun finishRun(reason:String){
        if(closing)return
        closing=true;timeout?.let(main::removeCallbacks);request="";continuation=null
        coordinator.cancelPending()
        sourceSink?.diagnosticCapture=null;tap?.finish();tap=null;VideoDiagnosticTrace.end()
        releaseWorker(kill=true)
        run.put("reason",reason).put("execution",if(reason.isEmpty())"completed"else"interrupted").put("endedAt",System.currentTimeMillis())
        val results=run.optJSONArray("results")?:JSONArray()
        val valid=(0 until results.length()).count{results.getJSONObject(it).optString("measurementValidity")=="valid"}
        run.put("measurementValidity",if(valid==results.length() && valid>0 && reason.isEmpty())"valid"else"partial")
            .put("evidenceCompleteness","partial") // Physical/theme/transport boundaries stay explicit.
        run.put("status",if(coordinator.unavailable)"storage_unavailable"else"restoring");coordinator.publishMemory()
        // Restoration must run even when storage is blocked or unavailable.
        val sink=suspendedSink;val ticket=diagnosticLease
        background("restore_original_video",8000,{
            val same=ticket?.let{com.shilapi.xcertplay.diagnostics.DiagnosticLease.owns(it,DiPlaySessionService.liveRuntime?.currentConnection?.controller)}==true
            val restored=if(sink==null)true else if(same)sink.resumeVideoAfterDiagnostics(checkNotNull(ticket).leaseId)
                else sink.diagnosticLeaseReleased(checkNotNull(ticket).leaseId)
            ticket?.let { current -> com.shilapi.xcertplay.diagnostics.DiagnosticLease.release(current,restored) {
                sink?.diagnosticLeaseReleased(current.leaseId) ?: true
            } }
            JSONObject().put("restored",restored).put("settingsAfter",settings())
        }){value->
            run.put("restoration",if(value.optBoolean("restored"))"video_lease_released; resumed_rendering_not_verified"else"waiting_for_actual_codec_release; no_new_decoder_allowed")
                .put("settingsAfter",value.optJSONObject("settingsAfter")?:JSONObject())
            if(value.has("error"))gap("restoration_unconfirmed:${value.optString("error")}")
            else if(!value.optBoolean("restored"))gap("restoration_waiting_for_actual_codec_release")
            suspendedSink=null
            if(coordinator.unavailable){run.put("status","storage_unavailable");coordinator.publishMemory();stopSelf()}else sealAndPublish()
        }
    }
    private fun storageUnavailable(reason: String) {
        run.put("reason","storage_unavailable:$reason")
        if (!run.has("id")) { coordinator.publishMemory(); stopSelf(); return }
        if (!closing) finishRun("storage_unavailable:$reason")
        else { releaseWorker(kill=true); coordinator.publishMemory(); stopSelf() }
    }
    private fun sealAndPublish(){
        run.put("publication","not_yet_performed")
        stage("sealing") {
            val artifacts=com.shilapi.xcertplay.diagnostics.RuntimeEvidence.capture(run.getString("id"))
            coordinator.artifacts(artifacts){coordinator.seal { publish() }}
        }
    }
    private fun publicationFailed(reason:String){
        releaseWorker(kill=true)
        run.put("publication","failed").put("publicationError",reason).put("status","interrupted")
        if (coordinator.unavailable) { coordinator.publishMemory(); stopSelf() }
        else checkpoint { stopSelf() }
    }
    private fun publish(){
        run.put("publication","writing")
        stage("publishing") {
            connect{
                send(DecoderDiagnosticsWorkerService.EXECUTE,JSONObject().put("operation","publish"),60000){value->
                    if(value.has("error"))run.put("publication","failed").put("publicationError",value.optString("error"))
                    else value.keys().forEach{key->run.put(key,value.get(key))}
                    run.put("status",if(run.optString("execution")=="completed")"completed"else"interrupted")
                    checkpoint {
                        coordinator.write("receipt.json",run.toString()) {
                            if(run.optString("publication")=="public_verified" && Build.VERSION.SDK_INT<=28){
                                val file=File(run.getString("publicPath"));val id=run.getString("id")
                                Thread({val index=DiagnosticExportStore.registerCompletedDownload(this,file,"application/zip")
                                    runCatching{DecoderDiagnosticsRun.json(File(DecoderDiagnosticsRun.directory(this,id),"download-index.json"),JSONObject().put("downloadId",index.first?:JSONObject.NULL).put("error",index.second?:JSONObject.NULL))}
                                },"diagnostic-download-index").apply{isDaemon=true;start()}
                            }
                            releaseWorker(kill=value.has("error"));stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
                        }
                    }
                }
            }
        }
    }
    private fun connect(next:()->Unit){
        if(worker!=null && workerPid>0){next();return}
        afterConnect=next
        if(!bound){bound=bindService(Intent(this,DecoderDiagnosticsWorkerService::class.java),connection,BIND_AUTO_CREATE)}
        main.postDelayed({if(afterConnect===next){afterConnect=null;run.put("publicationError","worker_bind_deadline");if(closing){publicationFailed("worker_bind_deadline")}else finishRun("worker_bind_deadline")}},5000)
    }
    private fun send(what:Int,spec:JSONObject,millis:Long,done:(JSONObject)->Unit){
        val id=UUID.randomUUID().toString();request=id;continuation=done
        timeout=Runnable{if(request==id){request="";continuation=null;done(JSONObject().put("error","operation_deadline_${spec.optString("operation","handshake")}"))}}.also{main.postDelayed(it,millis)}
        try{checkNotNull(worker).send(Message.obtain(null,what).apply{replyTo=reply;data=Bundle().apply{putString("request",id);putString("run",run.getString("id"));putString("spec",spec.toString())}})}
        catch(e:Exception){timeout?.let(main::removeCallbacks);request="";continuation=null;done(JSONObject().put("error",e.javaClass.simpleName))}
    }
    private fun background(name:String,millis:Long,work:()->JSONObject,done:(JSONObject)->Unit){
        val id=UUID.randomUUID().toString();request=id
        timeout=Runnable{if(request==id){
            request=""
            if(name in setOf("preflight","live_source_seal")) coordinator.markStorageUnavailable("${name}_deadline")
            else done(JSONObject().put("error","${name}_deadline"))
        }}.also{main.postDelayed(it,millis)}
        Thread({val value=runCatching(work).getOrElse{JSONObject().put("error","$name:${it.javaClass.simpleName}:${it.message}".take(300))}
            main.post{if(!destroyed && request==id){timeout?.let(main::removeCallbacks);timeout=null;request="";done(value)}}},"diagnostic-$name").apply{isDaemon=true;start()}
    }
    private fun releaseWorker(kill:Boolean){
        afterConnect=null;worker=null
        if(bound){runCatching{unbindService(connection)};bound=false}
        if(kill && workerPid>0 && workerPid!=Process.myPid())Process.killProcess(workerPid)
        workerPid=0
    }
    override fun onDestroy(){
        destroyed=true;closing=true;request="";continuation=null;afterConnect=null
        startGeneration++;alive=false
        coordinator.onStorageFailure=null
        if (!coordinator.recovering) coordinator.cancelPending()
        main.removeCallbacksAndMessages(null);sourceSink?.diagnosticCapture=null
        val sink=suspendedSink;val ticket=diagnosticLease
        if(ticket!=null)Thread({
            val same=com.shilapi.xcertplay.diagnostics.DiagnosticLease.owns(ticket,DiPlaySessionService.liveRuntime?.currentConnection?.controller)
            val released=runCatching{if(sink==null)true else if(same)sink.resumeVideoAfterDiagnostics(ticket.leaseId)
                else sink.diagnosticLeaseReleased(ticket.leaseId)}.getOrDefault(false)
            com.shilapi.xcertplay.diagnostics.DiagnosticLease.release(ticket,released){sink?.diagnosticLeaseReleased(ticket.leaseId)?:true}
        },"diagnostic-final-restore").apply{isDaemon=true;start()}
        releaseWorker(kill=true);super.onDestroy()
    }
    companion object{
        private const val STOP="decoder_diagnostics_stop";private const val RETRY="decoder_diagnostics_retry"
        @Volatile var alive=false;private set
        fun active(c:Context):Boolean {
            val state=DecoderDiagnosticsRun.coordinator(c);state.recover()
            return state.recovering || (alive && state.snapshot().optString("status") in DecoderDiagnosticsRun.busyStates)
        }
        fun start(c:Context,screen:JSONObject){c.startForegroundService(Intent(c,DecoderDiagnosticsService::class.java).putExtra("screen",screen.toString()))}
        fun stop(c:Context){c.startForegroundService(Intent(c,DecoderDiagnosticsService::class.java).setAction(STOP))}
        fun retry(c:Context){c.startForegroundService(Intent(c,DecoderDiagnosticsService::class.java).setAction(RETRY))}
    }
}
