package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject

/** A uniform source/availability envelope alongside the unmodified raw platform snapshot. */
internal object DecoderDiagnosticsFields {
    fun describe(snapshot:JSONObject):JSONObject {
        val fields=JSONArray();val time=System.currentTimeMillis()
        fun source(path:String)=when{
            path.contains("cpuAndKernel")||path.contains("thermalZones")->"public_proc_sysfs; raw_unverified_units_where_named_raw"
            path.contains("decoders")->"MediaCodecList.CodecCapabilities"
            path.contains("gpu")->"EGL14/GLES20"
            path.contains("screen")->"Display/DisplayMetrics"
            path.contains("software")||path.contains("appUid")->"PackageManager/Build/Process"
            path.contains("Storage")->"StatFs/Environment"
            path.contains("network")->"ConnectivityManager.NetworkCapabilities"
            path.contains("usb")->"UsbManager.enumeration_only"
            path.contains("memory")->"ActivityManager.MemoryInfo"
            path.contains("apkSha256")->"SHA256(applicationInfo.sourceDir)"
            else->"Android_public_platform_snapshot"
        }
        fun visit(path:String,value:Any?){
            if(value is JSONObject && !value.has("unavailable") && !value.has("error"))value.keys().forEach{visit(if(path.isEmpty())it else "$path.$it",value.opt(it))}
            else if(value is JSONArray)for(i in 0 until value.length())visit("$path[$i]",value.opt(i))
            else fields.put(JSONObject().put("field",path).put("value",value?:JSONObject.NULL).put("source",source(path)).put("wallMs",time)
                .put("availability",if(value==null || value===JSONObject.NULL || value is JSONObject)"unavailable_or_unknown"else"read")
                .put("confidence",if(path.contains("raw",true)||path.contains("thermal",true)||value is JSONObject)"unverified_interpretation"else"reported_not_independently_verified")
                .put("unit",when{path.contains("Bytes")->"bytes";path.contains("Kbps")->"kbps_system_estimate";path.contains("Celsius")->"celsius_battery_only";else->"raw_or_field_named_unit"})
                .put("error",if(value is JSONObject)value.opt("unavailable")?:value.opt("error")?:JSONObject.NULL else JSONObject.NULL))
        }
        visit("",snapshot)
        return JSONObject().put("fields",fields).put("collectedAt",time).put("rawSnapshot","device/inventory.json")
    }
}
