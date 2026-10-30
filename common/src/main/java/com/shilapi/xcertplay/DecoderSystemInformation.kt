package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded public probes only. Unreadable fields are evidence, never guessed chip identities. */
internal object DecoderSystemInformation {
    fun collect(c: Context): JSONObject {
        val missing = JSONArray()
        fun probe(name: String, read: () -> Any): Any = try { read() } catch (error: Exception) {
            val why = error.javaClass.simpleName
            missing.put("$name:$why"); JSONObject().put("unavailable", why)
        }
        fun text(path: String, limit: Int = 16_384): String = File(path).inputStream().use {
            val bytes = ByteArray(limit); val size = it.read(bytes)
            String(bytes, 0, size.coerceAtLeast(0), Charsets.UTF_8).lineSequence()
                .filterNot { line -> line.trimStart().startsWith("Serial", true) }.joinToString("\n")
        }
        val cpu = JSONObject()
        for ((key, path) in listOf("cpuInfo" to "/proc/cpuinfo", "online" to "/sys/devices/system/cpu/online",
            "present" to "/sys/devices/system/cpu/present", "kernel" to "/proc/version", "memoryInfo" to "/proc/meminfo"))
            cpu.put(key, probe(key) { text(path) })
        val frequencies = JSONArray()
        for (index in 0 until Runtime.getRuntime().availableProcessors().coerceIn(1, 32)) {
            val row = JSONObject().put("logicalCpu", index)
            for (field in listOf("scaling_cur_freq", "scaling_min_freq", "scaling_max_freq", "cpuinfo_max_freq", "scaling_governor")) {
                val path = "/sys/devices/system/cpu/cpu$index/cpufreq/$field"
                row.put(field, probe("cpu$index/$field") { text(path, 256).trim() })
            }
            frequencies.put(row)
        }
        cpu.put("frequencies", frequencies).put("frequencyUnit", "kHz_when_reported_by_cpufreq")
        val thermal = JSONArray()
        for (i in 0 until 32) {
            val folder = File("/sys/class/thermal/thermal_zone$i")
            if (!folder.exists()) continue
            thermal.put(JSONObject().put("zone", i).put("type", probe("thermal$i/type") { text("$folder/type", 256).trim() })
                .put("rawTemperature", probe("thermal$i/temp") { text("$folder/temp", 256).trim() })
                .put("interpretation", "unverified_sensor_identity_and_units_do_not_assume_CPU_temperature"))
        }
        fun storage(path: String): JSONObject = StatFs(path).let {
            JSONObject().put("path", path).put("totalBytes", it.totalBytes).put("availableBytes", it.availableBytes)
        }
        val software = probe("application") {
            @Suppress("DEPRECATION") val info = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS)
            JSONObject().put("package", c.packageName).put("versionName", info.versionName)
                .put("versionCode", info.longVersionCode).put("targetSdk", c.applicationInfo.targetSdkVersion)
                .put("minSdk", c.applicationInfo.minSdkVersion).put("uid", android.os.Process.myUid())
                .put("permissions", JSONArray(info.requestedPermissions.orEmpty().map {
                    JSONObject().put("name", it).put("granted", c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED)
                }))
                .put("securityPatch", Build.VERSION.SECURITY_PATCH).put("buildType", Build.TYPE)
                .put("buildTags", Build.TAGS).put("supported32BitAbis", JSONArray(Build.SUPPORTED_32_BIT_ABIS.toList()))
                .put("supported64BitAbis", JSONArray(Build.SUPPORTED_64_BIT_ABIS.toList()))
        }
        val network = probe("network") {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val cap = cm.getNetworkCapabilities(active)
            JSONObject().put("connected", active != null).put("transports", JSONArray(listOf(0, 1, 2, 3, 4).filter { cap?.hasTransport(it) == true }))
                .put("internet", cap?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)
                .put("validated", cap?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
                .put("linkDownstreamKbps", cap?.linkDownstreamBandwidthKbps ?: JSONObject.NULL)
                .put("linkUpstreamKbps", cap?.linkUpstreamBandwidthKbps ?: JSONObject.NULL)
                .put("scope", "system_estimates_not_measured_wifi_throughput")
        }
        val usb = probe("usb") {
            val manager = c.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            JSONArray(manager.deviceList.values.map { d -> JSONObject().put("vendorId", d.vendorId).put("productId", d.productId)
                .put("deviceClass", d.deviceClass).put("interfaces", d.interfaceCount).put("permission", manager.hasPermission(d)) })
        }
        return JSONObject().put("usb", usb).put("cpuAndKernel", cpu).put("thermalZones", thermal).put("software", software)
            .put("appStorage", probe("app_storage") { storage(c.filesDir.absolutePath) })
            .put("sharedStorage", probe("shared_storage") { storage(Environment.getExternalStorageDirectory().absolutePath) })
            .put("sharedStorageState", Environment.getExternalStorageState()).put("network", network)
            .put("unavailable", missing).put("coverageLimits", JSONArray(listOf(
                "No root/private vendor interfaces", "CPU/GPU clocks are snapshots, not load or throttling proof",
                "Panel model, true physical DPI and vendor display postprocessing may be unavailable",
                "No installed-app inventory, device serial, MAC or account identifiers collected")))
    }
}
