package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.*
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A disposable rendering window. It never owns the overall run or a production CarPlay connection. */
class DecoderDiagnosticsActivity : Activity() {
    private val main=Handler(Looper.getMainLooper())
    private var texture:TextureView?=null
    private var direct:SurfaceView?=null
    private var wrapper:Surface?=null
    private val running=AtomicBoolean(true)
    private val started=AtomicBoolean(false)
    private var completed=false
    @Volatile private var covered=false
    private lateinit var ticket:DecoderDiagnosticsWorkerService.Ticket
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val current=DecoderDiagnosticsWorkerService.ticket
        val requestId=intent.getStringExtra("request")
        if(current==null || current.id!=requestId){finish();return}
        ticket=current
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val spec=ticket.spec
        if(spec.getBoolean("surface")) {
            direct=SurfaceView(this).also { view ->
                root.addView(view,FrameLayout.LayoutParams(-1,-1))
                view.holder.setFixedSize(spec.optInt("width",1920),spec.optInt("height",1080))
                view.holder.addCallback(object:SurfaceHolder.Callback {
                    override fun surfaceCreated(holder:SurfaceHolder){launch(holder.surface)}
                    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){}
                    override fun surfaceDestroyed(holder:SurfaceHolder){running.set(false)}
                })
            }
        } else {
            texture=TextureView(this).also { view ->
                root.addView(view,FrameLayout.LayoutParams(-1,-1))
                view.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st:SurfaceTexture,w:Int,h:Int){
                        st.setDefaultBufferSize(spec.optInt("width",1920),spec.optInt("height",1080))
                        wrapper=Surface(st);launch(wrapper!!)
                    }
                    override fun onSurfaceTextureSizeChanged(st:SurfaceTexture,w:Int,h:Int){}
                    override fun onSurfaceTextureDestroyed(st:SurfaceTexture):Boolean{running.set(false);return false}
                    override fun onSurfaceTextureUpdated(st:SurfaceTexture){}
                }
            }
        }
        root.addView(TextView(this).apply {
            text="自动诊断 · ${spec.getString("caseId")}\n正在预热／测量／截图，结束后自动返回"
            setTextColor(Color.WHITE);setBackgroundColor(0x99000000.toInt());textSize=16f
        },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(root)
        main.postDelayed({
            if(!started.get() && !completed && !isFinishing && !isDestroyed){
                completed=true
                DecoderDiagnosticsWorkerService.complete(ticket, JSONObject().put("caseId", spec.getString("caseId"))
                    .put("windowInterrupted", true).put("cleanupConfirmed", true).put("error", "surface_creation_timeout_8s"))
                finish()
            }
        }, 8000)
    }
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
    override fun onStop(){super.onStop();if(!completed){covered=true;running.set(false)}}
    override fun onDestroy(){running.set(false);super.onDestroy()}
    @Deprecated("Deprecated in Java")
    override fun onBackPressed(){covered=true;running.set(false)}
    private fun launch(surface:Surface){
        if(!started.compareAndSet(false,true))return
        Thread({execute(surface)},"diagnostic-fixture").apply { isDaemon=true;start() }
    }
    private fun execute(surface:Surface){
        val spec=ticket.spec;val id=spec.getString("caseId")
        val directory=DecoderDiagnosticsRun.directory(this,ticket.run)
        val result=DecoderDiagnosticsRun.result(spec,"failed")
        val logs=java.util.concurrent.ConcurrentLinkedQueue<String>()
        var sink:AndroidMediaSink?=null;var reader:MediaExtractor?=null
        var trace:VideoDiagnosticTrace.Snapshot?=null
        var captureCompletionConfirmed=true
        try {
            val replay=spec.optBoolean("replay")
            val source=if(replay)DecoderDiagnosticsRun.read(File(directory,"inputs/source.json"))else JSONObject()
            val asset=spec.getString("asset")
            val codec=if(replay)VideoCodec.valueOf(source.getString("codec"))else if(asset.startsWith("hevc"))VideoCodec.H265 else VideoCodec.H264
            val csd=if(replay)File(directory,"inputs/source.csd").readBytes()else assets.open("color-test/$asset.csd").use{it.readBytes()}
            val width:Int;val height:Int;val fps:Int
            if(replay)result.put("replayCadenceBasis",source.optString("replayCadenceBasis")).put("replayScope",source.optString("replayScope"))
            val replayFrames=if(replay) source.getJSONArray("accessUnits")else JSONArray()
            val replayBytes=if(replay)File(directory,"inputs/source.bin").readBytes()else ByteArray(0)
            if(replay){
                check(source.optBoolean("strictReplay"));check(DecoderDiagnosticsRun.sha(File(directory,"inputs/source.bin"))==source.getString("sha256"))
                width=source.getInt("width");height=source.getInt("height");fps=30
            } else {
                val extractor=MediaExtractor().also { reader=it }
                assets.openFd("color-test/$asset.mp4").use { extractor.setDataSource(it.fileDescriptor,it.startOffset,it.length) }
                extractor.selectTrack(0);val format=extractor.getTrackFormat(0)
                width=format.getInteger(MediaFormat.KEY_WIDTH);height=format.getInteger(MediaFormat.KEY_HEIGHT)
                fps=runCatching{format.getInteger(MediaFormat.KEY_FRAME_RATE)}.getOrDefault(10).coerceIn(1,120)
            }
            result.put("inputWidth",width).put("inputHeight",height).put("sourceHash",if(replay)source.getString("sha256")else spec.optString("sourceHash"))
            val plan=VideoPlanResolver.resolve(VideoPipelineRequest(codec=codec,width=width,height=height,fps=fps,
                direct=spec.getBoolean("surface"),forceSoftware=spec.getBoolean("software"),
                softwareYuv=SoftwareYuvConfig().takeIf{spec.optBoolean("softwareYuv")},hardwareYuv=spec.optBoolean("hardwareYuv"),
                color=VideoColorOverride(fromSps=spec.optBoolean("fromSps")),purpose=VideoPurpose.DIAGNOSTIC))
            result.put("videoPlan",JSONObject(plan.report()))
            fun createSink()=AndroidMediaSink(videoPipelinePlan=plan,surface=surface,videoWidth=width,videoHeight=height,
                forceSoftwareVideoDecoder=spec.getBoolean("software"),mainScreenSoftwareYuv=if(spec.optBoolean("softwareYuv"))SoftwareYuvConfig()else null,
                mainScreenHardwareYuv=spec.optBoolean("hardwareYuv"),videoColorOverride=VideoColorOverride(fromSps=spec.optBoolean("fromSps")))
            if(spec.getBoolean("software") && diagnosticSoftwareDecoderName(diagnosticSoftwareCandidates(),if(codec==VideoCodec.H264)MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC)==null)
                throw UnsupportedOperationException("no_confirmed_platform_software_decoder")
            val output=createSink()
            sink=output
            output.setVideoDiagnosticHandler(110){line->if(logs.size<128)logs.add(line.take(1200))}
            val initStart=SystemClock.elapsedRealtime()
            output.onVideoCodec(110,codec);output.onVideoConfig(110,csd)
            awaitConfigured(output,initStart+5000)
            result.put("decoderInitMs",SystemClock.elapsedRealtime()-initStart)
            val buffer=ByteBuffer.allocate(8*1024*1024)
            val firstDeadline=SystemClock.elapsedRealtime()+5000
            var firstAt=0L;var measureAt=0L;var endAt=0L;var frameIndex=0;var nextFrame=System.nanoTime()
            var before:LiveVideoCounters?=null;var after:LiveVideoCounters?=null
            var cpuBefore=0L;var cpuAfter=0L;var lastLoad=0L;var actualEnd=0L
            val load=JSONArray()
            while(running.get()){
                val bytes:ByteArray;val interval:Long
                if(replay){
                    val frame=replayFrames.getJSONObject(frameIndex)
                    val offset=frame.getInt("offset");val length=frame.getInt("bytes")
                    check(offset>=0 && length>0 && offset.toLong()+length<=replayBytes.size)
                    bytes=replayBytes.copyOfRange(offset,offset+length)
                    val next=if(frameIndex+1<replayFrames.length())replayFrames.getJSONObject(frameIndex+1).getLong("receivedNs")-frame.getLong("receivedNs") else source.optLong("lastIntervalNs",33_333_333)
                    interval=next.coerceAtLeast(1)
                    frameIndex=(frameIndex+1)%replayFrames.length()
                } else {
                    buffer.clear();val size=reader!!.readSampleData(buffer,0)
                    if(size<0){reader.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC);continue}
                    check(size<=buffer.capacity());bytes=ByteArray(size);buffer.position(0);buffer.get(bytes);reader.advance()
                    interval=1_000_000_000L/fps
                }
                output.onVideoFrame(110,bytes)
                val now=SystemClock.elapsedRealtime()
                if(firstAt==0L && (output.liveVideoCounters()?.rendered?:0)>0)firstAt=now
                check(firstAt>0 || now<firstDeadline){"first_frame_deadline_5s"}
                if(firstAt>0 && measureAt==0L && now-firstAt>=spec.getLong("warmupMs")){
                    measureAt=now;endAt=now+spec.getLong("measureMs");before=output.liveVideoCounters();cpuBefore=Process.getElapsedCpuTime()
                    VideoDiagnosticTrace.begin()
                }
                if(measureAt>0 && now-lastLoad>=2000){lastLoad=now;load.put(DecoderDiagnosticsLoad.sample(this))}
                if(measureAt>0 && now>=endAt){after=output.liveVideoCounters();cpuAfter=Process.getElapsedCpuTime();actualEnd=SystemClock.elapsedRealtime();trace=VideoDiagnosticTrace.end();break}
                nextFrame+=interval
                val sleep=(nextFrame-System.nanoTime())/1_000_000
                if(sleep>0)Thread.sleep(sleep)
                // Preserve source cadence. A >250ms catch-up is evidence of feed starvation, not a silent reset.
                if(System.nanoTime()-nextFrame>250_000_000){result.put("feedCadenceMissed",true);nextFrame=System.nanoTime()}
            }
            trace=trace?:VideoDiagnosticTrace.end()
            val evidence=output.videoColorEvidence()
            result.put("sourceEvidence",ColorProbeEvidence.capture(evidence,true,spec.getBoolean("software"),emptyList(),if(replay)"same_source_replay"else"standard_asset",spec))
            val actual=evidence?.output
            val softwareConfirmed=actual?.software==true
            val hardwareKnown=actual?.decoder?.let{it.contains("mtk",true)||it.contains("qcom",true)||it.contains("exynos",true)}==true
            val format=actual?.format.orEmpty()
            val yuvCorrect=when(spec.getString("path")){"P5"->format["software-yuv"]==1;"P6"->format["hardware-yuv"]==1;else->true}
            val decoderMatches=if(spec.getBoolean("software"))softwareConfirmed else hardwareKnown
            val valid=before!=null && after!=null && !covered && !result.optBoolean("feedCadenceMissed") && yuvCorrect && decoderMatches
            result.put("actualDecoder",actual?.decoder?:JSONObject.NULL).put("actualSoftware",if(softwareConfirmed)true else if(hardwareKnown)false else JSONObject.NULL)
                .put("actualOutput",if(format["hardware-yuv"]==1)"hardware_yuv" else if(format["software-yuv"]==1)"software_yuv"else if(spec.getBoolean("surface"))"surface" else "texture")
                .put("requestedPathConfirmed",yuvCorrect && decoderMatches).put("execution",if(before!=null && after!=null)"completed"else"interrupted")
                .put("performanceValidity",if(valid)"valid"else"invalid").put("measurementValidity",if(valid)"valid"else"invalid").put("reason",when{covered->"window_covered_or_closed";!yuvCorrect->"requested_yuv_path_not_active";!decoderMatches->"requested_decoder_not_confirmed";result.optBoolean("feedCadenceMissed")->"feed_cadence_missed";else->""})
                .put("load",load).put("warmupMs",spec.getLong("warmupMs")).put("measureMs",if(actualEnd>measureAt)actualEnd-measureAt else 0)
            if(before!=null && after!=null)result.put("receivedFrames",after.received-before.received).put("submittedFrames",after.rendered-before.rendered)
                .put("processCpuCorePercent",(cpuAfter-cpuBefore)*100.0/(actualEnd-measureAt)).put("outputFps",(after.rendered-before.rendered)*1000.0/(actualEnd-measureAt))
            result.put("timing",DecoderDiagnosticsMetrics.trace(trace,File(directory,"traces/$id.tsv")))
            if(running.get() && after!=null){
                // Captures use a fresh instance of the SAME path and freeze its first decoded picture.
                // This makes all paths sample the first random-access picture of the identical input.
                output.close();check(output.awaitVideoReleased(5000)){"measurement_decoder_release_unconfirmed"}
                val captureSink=createSink();sink=captureSink
                captureSink.setVideoDiagnosticHandler(110){line->if(logs.size<128)logs.add("capture: "+line.take(1100))}
                captureSink.onVideoCodec(110,codec);captureSink.onVideoConfig(110,csd);captureSink.freezeFirstOutputForDiagnostics()
                awaitConfigured(captureSink,SystemClock.elapsedRealtime()+5000)
                if(!replay)reader!!.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var captureIndex=0
                var firstAccessUnitBytes:ByteArray?=null
                val isFixedLightOrDark = asset.startsWith("h264_light_") || asset.startsWith("h264_dark_")
                val refObj = if (isFixedLightOrDark) runCatching {
                    assets.open("color-test/h1-reference.json").bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("files").getJSONObject(asset)
                }.getOrNull() else null

                val samplePoints = ArrayList<Pair<Int, Int>>()
                val patchIndices = ArrayList<Int?>()
                val nominals = ArrayList<Int?>()
                if (refObj != null) {
                    val patches = refObj.getJSONArray("patches")
                    val patchCount = minOf(patches.length(), 24)
                    for (i in 0 until patchCount) {
                        val p = patches.getJSONObject(i)
                        val rect = p.getJSONArray("rect")
                        val cx = rect.getInt(0) + rect.getInt(2) / 2
                        val cy = rect.getInt(1) + rect.getInt(3) / 2
                        samplePoints.add(Pair(cx, cy))
                        patchIndices.add(i)
                        nominals.add(p.optInt("nominal", 0))
                    }
                }
                val captureFrameId = if(replay)replayFrames.getJSONObject(0).getString("frameId")else"$asset:first_random_access_picture"
                if (samplePoints.isNotEmpty()) {
                    captureSink.configureDiagnosticSampling(samplePoints, patchIndices, nominals, captureFrameId, "capture_sink")
                }

                val captureWindow=DecoderDiagnosticsCaptureWindow(SystemClock.elapsedRealtime()+5000)
                while(running.get()){
                    when(captureWindow.action(SystemClock.elapsedRealtime(),captureSink.liveVideoCounters()?.rendered?:0)){
                        DecoderDiagnosticsCaptureWindow.Action.COMPLETE->break
                        DecoderDiagnosticsCaptureWindow.Action.TIMEOUT->error("capture_first_frame_deadline")
                        DecoderDiagnosticsCaptureWindow.Action.WAIT->{Thread.sleep(10);continue}
                        DecoderDiagnosticsCaptureWindow.Action.READ->Unit
                    }
                    val bytes=if(replay){
                        val f=replayFrames.getJSONObject(captureIndex);captureIndex++
                        replayBytes.copyOfRange(f.getInt("offset"),f.getInt("offset")+f.getInt("bytes"))
                    }else{
                        buffer.clear();val size=reader!!.readSampleData(buffer,0)
                        if(size<0){captureWindow.exhausted=true;captureSink.finishInputForDiagnostics();continue}
                        check(size>0){"empty_capture_access_unit"}
                        ByteArray(size).also{buffer.position(0);buffer.get(it);reader!!.advance()}
                    }
                    if(firstAccessUnitBytes==null)firstAccessUnitBytes=bytes
                    captureSink.onVideoFrame(110,bytes)
                    if(replay && captureIndex>=replayFrames.length()){captureWindow.exhausted=true;captureSink.finishInputForDiagnostics()}
                    Thread.sleep(33)
                }
                check((captureSink.liveVideoCounters()?.rendered?:0)>0){"capture_first_frame_deadline"}
                Thread.sleep(100)
                val captureEvidence=captureSink.videoColorEvidence()
                var rawSamples = captureSink.latestRawYuvSamples()
                var rawLocationOverride: String? = null
                var rawYuvDecoderComponent: String? = null
                var rawYuvError: String? = null
                val requiresThreeLayerEvidence = isFixedLightOrDark || (refObj != null && samplePoints.isNotEmpty())

                if (rawSamples.isNotEmpty()) {
                    rawLocationOverride = "capture_yuv_instance"
                    rawYuvDecoderComponent = captureEvidence?.output?.decoder
                } else if (firstAccessUnitBytes != null && requiresThreeLayerEvidence) {
                    try {
                        val (comp, decoded) = decodeNoSurfaceRawYuv(
                            codec = codec,
                            csd = csd,
                            frameBytes = firstAccessUnitBytes!!,
                            points = samplePoints,
                            patchIndices = patchIndices,
                            nominals = nominals,
                            frameId = captureFrameId,
                            software = spec.optBoolean("software", false)
                        )
                        rawYuvDecoderComponent = comp
                        rawSamples = decoded
                        if (rawSamples.isNotEmpty()) {
                            rawLocationOverride = "diagnostic_no_surface_instance"
                        } else {
                            rawYuvError = "no_surface_raw_yuv_empty"
                        }
                    } catch (e: Exception) {
                        rawYuvError = "${e.javaClass.simpleName}:${e.message}".take(200)
                    }
                } else if (requiresThreeLayerEvidence) {
                    rawYuvError = "missing_first_access_unit"
                }

                if (rawYuvDecoderComponent != null) {
                    result.put("rawYuvDecoderComponent", rawYuvDecoderComponent)
                }
                if (rawYuvError != null) {
                    result.put("rawYuvError", rawYuvError)
                }

                val pairing = DecoderPixelPairing.evaluate(evidence, captureEvidence, replay, rawSamples, rawLocationOverride)
                result.put("captureSourceEvidence",ColorProbeEvidence.capture(captureEvidence,true,spec.getBoolean("software"),emptyList(),if(replay)"same_source_frozen_first_picture"else"standard_frozen_first_picture",spec))
                pairing.applyTo(result)
                result.put("captureFrameId",captureFrameId)
                    .put("captureFrameBasis","fresh_same_path_decoder_first_output_frozen; input_starts_at_random_access")
                if(!pairing.capturePathMatches)result.put("measurementValidity","invalid").put("reason","capture_decoder_or_output_configuration_changed")
                val latch=CountDownLatch(1);val captureResult=JSONObject();val captureOpen=AtomicBoolean(true)
                main.post{if(captureOpen.get())capture(width,height,id,directory,captureResult,captureOpen,pairing.rawYuvSamples){latch.countDown()}}
                if(latch.await(5,TimeUnit.SECONDS))captureResult.keys().forEach{key->result.put(key,captureResult.get(key))}
                else { captureOpen.set(false);captureCompletionConfirmed=false;result.put("captureError","capture_deadline_5s").put("measurementValidity","invalid") }

                DecoderPixelPairing.recordEvidenceCompleteness(
                    result = result,
                    requiresThreeLayerEvidence = requiresThreeLayerEvidence,
                    rawSamples = rawSamples,
                    expectedPatchIndices = patchIndices.filterNotNull().toSet(),
                    captureFrameId = captureFrameId,
                    traceDropped = trace.dropped,
                )
            }
            if (!result.has("evidenceCompleteness")) {
                result.put("evidenceCompleteness", if (result.has("capture") && trace.dropped == 0) "complete" else "partial")
            }
        } catch(e:Exception){result.put("execution","failed").put("measurementValidity","invalid").put("evidenceCompleteness","partial")
            result.put("reason","${e.javaClass.simpleName}:${e.message}".take(300))
            if(e is UnsupportedOperationException)result.put("execution","unsupported")
        }
        finally {
            VideoDiagnosticTrace.end();runCatching{reader?.release()}
            if(covered)result.put("execution","interrupted").put("measurementValidity","invalid").put("reason","window_covered_or_closed")
            // Persist evidence before entering a vendor release call that may never return.
            runCatching {
                result.put("cleanupConfirmed",false).put("cleanupStatus","pending")
                result.optJSONArray("load")?.let { DecoderDiagnosticsRun.atomic(File(directory,"device/load-$id.json"),it.toString().toByteArray()) }
                result.remove("load");result.put("loadTrace","device/load-$id.json")
                DecoderDiagnosticsRun.json(File(directory,"cases/$id.json"),result)
            }
            val released=runCatching{sink?.close();sink?.awaitVideoReleased(5000)?:true}.getOrDefault(false)
            result.put("cleanupStatus",if(released)"released"else"unconfirmed").put("decoderCleanupConfirmed",released).put("captureCompletionConfirmed",captureCompletionConfirmed)
                .put("cleanupConfirmed",released && captureCompletionConfirmed).put("logs",JSONArray(logs.toList()))
            runCatching{
                result.optJSONArray("load")?.let { DecoderDiagnosticsRun.atomic(File(directory,"device/load-$id.json"),it.toString().toByteArray()) }
                result.remove("load");result.put("loadTrace","device/load-$id.json")
                DecoderDiagnosticsRun.json(File(directory,"cases/$id.json"),result)
            }
            main.post {
                // Commit completion on the same event loop as Back/Home/onStop, after release.
                completed=true
                if(covered){
                    result.put("execution","interrupted").put("measurementValidity","invalid").put("reason","window_covered_or_closed")
                    runCatching{DecoderDiagnosticsRun.json(File(directory,"cases/$id.json"),result)}
                }
                DecoderDiagnosticsWorkerService.complete(ticket,JSONObject().put("caseId",id)
                    .put("windowInterrupted",covered).put("cleanupConfirmed",released && captureCompletionConfirmed))
                finish();wrapper?.release();texture?.surfaceTexture?.release()
            }
        }
    }
    private fun awaitConfigured(sink:AndroidMediaSink,deadline:Long){
        while(running.get() && sink.videoColorEvidence()?.output?.active!=true && SystemClock.elapsedRealtime()<deadline)Thread.sleep(10)
        check(running.get() && sink.videoColorEvidence()?.output?.active==true){"decoder_init_deadline_5s"}
    }
    private fun capture(width:Int,height:Int,id:String,directory:File,result:JSONObject,open:AtomicBoolean,rawSamples:List<Map<String,Any?>> = emptyList(),done:()->Unit){
        if(!running.get() || !open.get()){done();return}
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        fun save(image:Bitmap?){
            try{
                if(!open.get())return
                if(image==null){result.put("captureError","no_bitmap");return}
                val file=File(directory,"captures/$id.png");file.parentFile?.mkdirs()
                val temporary=File(file.parentFile,file.name+".writing")
                temporary.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                if(!open.compareAndSet(true,false)){temporary.delete();return}
                check(temporary.renameTo(file))
                result.put("capture","captures/$id.png").put("captureWidth",width).put("captureHeight",height)
                    .put("capturePath",if(texture!=null)"texture_getBitmap"else"surface_PixelCopy")
                    .put("viewWidth",texture?.width?:direct?.width).put("viewHeight",texture?.height?:direct?.height)
                    .put("bufferRequestedWidth",ticket.spec.optInt("width",1920)).put("bufferRequestedHeight",ticket.spec.optInt("height",1080))
                    .put("geometryScope","full_buffer_to_view; capture_may_include_texture_sampling; not_physical_panel")
                val asset=ticket.spec.getString("asset")
                if(asset=="h264_clarity1080")result.put("clarity",ClarityReferenceMetrics.measure(image))
                if(asset.startsWith("h264_light_")||asset.startsWith("h264_dark_")){
                    val reference=assets.open("color-test/h1-reference.json").bufferedReader().use{JSONObject(it.readText())}.getJSONObject("files").getJSONObject(asset)
                    result.put("highlights",DecoderDiagnosticsMetrics.highlights(image,reference,rawSamples))
                }
            }catch(e:Exception){result.put("captureError",e.javaClass.simpleName)}finally{image?.recycle();open.set(false);done()}
        }
        if(texture!=null){bitmap.recycle();save(texture?.getBitmap(width,height))}
        else try{PixelCopy.request(direct!!,bitmap,{code->if(code==PixelCopy.SUCCESS)save(bitmap)else{bitmap.recycle();result.put("captureError",code);done()}},main)}
        catch(e:Exception){bitmap.recycle();result.put("captureError",e.javaClass.simpleName);done()}
    }

    private fun decodeNoSurfaceRawYuv(
        codec: VideoCodec,
        csd: ByteArray,
        frameBytes: ByteArray,
        points: List<Pair<Int, Int>>,
        patchIndices: List<Int?>,
        nominals: List<Int?>,
        frameId: String,
        software: Boolean,
    ): Pair<String, List<Yuv420Frame.RawSample>> {
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val csdList = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(csd).takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(csd)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { startCode + it },
                pps.takeIf { it.isNotEmpty() }?.let { startCode + it },
            )
        }
        val annexB = MediaCodecSupport.toAnnexB(frameBytes)
        check(annexB.isNotEmpty()) { "annex_b_conversion_failed" }

        val softwareCandidate = if (software) {
            diagnosticSoftwareDecoderName(diagnosticSoftwareCandidates(), mime) ?: softwareYuvDecoderName(mime)
        } else null
        val decoder = if (softwareCandidate != null) {
            MediaCodec.createByCodecName(softwareCandidate)
        } else {
            MediaCodec.createDecoderByType(mime)
        }
        val actualComponentName = runCatching { decoder.name }.getOrDefault("unknown")

        try {
            val format = MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                csdList.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
            }
            decoder.configure(format, null, null, 0)
            decoder.start()

            val inIndex = decoder.dequeueInputBuffer(1_000_000)
            check(inIndex >= 0) { "no_input_buffer_available" }
            val inBuf = checkNotNull(decoder.getInputBuffer(inIndex)) { "decoder_input_buffer_null" }
            inBuf.clear()
            inBuf.put(annexB)
            decoder.queueInputBuffer(inIndex, 0, annexB.size, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            val info = MediaCodec.BufferInfo()
            var samples: List<Yuv420Frame.RawSample> = emptyList()
            for (attempt in 0..30) {
                val outIndex = decoder.dequeueOutputBuffer(info, 50_000)
                if (outIndex >= 0) {
                    try {
                        val image = decoder.getOutputImage(outIndex)
                        if (image != null && samples.isEmpty()) {
                            try {
                                val frame = Yuv420Frame().apply { copy(image) }
                                samples = frame.sample(points, patchIndices, nominals, frameId, actualComponentName)
                            } finally {
                                image.close()
                            }
                        }
                    } finally {
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || samples.isNotEmpty()) {
                    break
                }
            }
            return Pair(actualComponentName, samples)
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }
}
