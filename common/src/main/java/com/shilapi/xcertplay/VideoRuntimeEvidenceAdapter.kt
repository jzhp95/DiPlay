package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.video.RuntimeSnapshot
import com.shilapi.xcertplay.media.video.SampleResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视频运行时诊断证据转换适配器 (VideoRuntimeEvidenceAdapter)。
 * 将新管线 RuntimeSnapshot 与生产同帧采样结果转化为符合规范的不可变 JSON 节点。
 */
object VideoRuntimeEvidenceAdapter {

    fun snapshotToJson(snapshot: RuntimeSnapshot): JSONObject {
        return JSONObject()
            .put("sessionId", snapshot.sessionId)
            .put("streamRole", snapshot.streamRole)
            .put("requestedImplementation", snapshot.requestedImplementation.name)
            .put("actualImplementation", snapshot.actualImplementation.name)
            .put("actualBackend", snapshot.actualBackend?.name ?: JSONObject.NULL)
            .put("activityState", snapshot.activityState.name)
            .put("releaseState", snapshot.releaseState.name)
            .put("configGeneration", snapshot.configGeneration)
            .put("decoderGeneration", snapshot.decoderGeneration)
            .put("surfaceGeneration", snapshot.surfaceGeneration)
            .put("fallbackReason", snapshot.fallbackReason ?: JSONObject.NULL)
            .put("lastError", snapshot.lastError ?: JSONObject.NULL)
            .put("appOwnedBytes", snapshot.appOwnedBytes)
            .put("inputQueueDepth", snapshot.inputQueueDepth)
            .put("activeLeaseCount", snapshot.activeLeaseCount)
    }

    fun sampleResultToJson(sampleResult: SampleResult): JSONObject {
        val samplesArray = JSONArray()
        sampleResult.samples.forEach { s ->
            samplesArray.put(
                JSONObject()
                    .put("x", s.x)
                    .put("y", s.y)
                    .put("yVal", s.yVal)
                    .put("uVal", s.uVal)
                    .put("vVal", s.vVal)
            )
        }

        return JSONObject()
            .put("frameId", sampleResult.frameKey.accessUnitId)
            .put("codecPtsUs", sampleResult.frameKey.codecPtsUs)
            .put("samples", samplesArray)
    }
}
