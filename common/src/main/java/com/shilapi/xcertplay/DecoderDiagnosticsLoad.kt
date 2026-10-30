package com.shilapi.xcertplay

import android.content.Context
import android.os.Debug
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object DecoderDiagnosticsLoad {
    fun sample(c:Context):JSONObject {
        fun read(path:String):Any=runCatching{File(path).inputStream().use{input->val b=ByteArray(2048);val n=input.read(b);String(b,0,n.coerceAtLeast(0)).trim()}}.getOrElse{JSONObject().put("unavailable",it.javaClass.simpleName)}
        val frequency=JSONArray()
        for(i in 0 until Runtime.getRuntime().availableProcessors().coerceAtMost(32))frequency.put(JSONObject().put("cpu",i)
            .put("rawKHz",read("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")))
        val thermal=JSONArray()
        for(i in 0 until 32)if(File("/sys/class/thermal/thermal_zone$i").exists())thermal.put(JSONObject().put("zone",i)
            .put("type",read("/sys/class/thermal/thermal_zone$i/type")).put("raw",read("/sys/class/thermal/thermal_zone$i/temp")))
        val threads=JSONArray()
        File("/proc/self/task").listFiles()?.take(256)?.forEach{folder->
            if(folder.name.all(Char::isDigit))threads.put(JSONObject().put("tid",folder.name).put("rawStat",read("$folder/stat")))
        }
        return JSONObject().put("threadStats",threads).put("threadStatsUnit","raw_proc_stat; user_system_ticks_not_converted_without_clock_tick_rate").put("localNs",System.nanoTime()).put("wallMs",System.currentTimeMillis())
            .put("source","public_proc_sysfs; raw_thermal_mapping_unverified").put("availability","per_field")
            .put("processCpuMs",Process.getElapsedCpuTime()).put("pid",Process.myPid()).put("uid",Process.myUid())
            .put("nativeAllocatedBytes",Debug.getNativeHeapAllocatedSize()).put("javaUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
            .put("frequencies",frequency).put("thermal",thermal).put("processStatus",read("/proc/self/status"))
    }
}
