package com.shilapi.xcertplay.diagnostics

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Called only by the independent diagnostic worker. APK content identifies the asset generation. */
internal class DiagnosticSourceIndex(
    private val root: File,
    private val files: DiagnosticRunStore.FileAccess = DiagnosticRunStore.DiskFileAccess,
) {
    fun load(apkSha256: String, assetNames: List<String>, open: (String) -> InputStream): JSONObject {
        require(apkSha256.matches(Regex("[a-f0-9]{64}")))
        val names=assetNames.distinct()
        require(names.isNotEmpty() && names.all { it.matches(Regex("[A-Za-z0-9_-]+")) })
        val target=File(root,"$apkSha256.json")
        val cached=runCatching {
            if (!files.exists(target)) null else JSONObject(files.read(target).toString(Charsets.UTF_8)).also { value ->
                require(value.getInt("schemaVersion")==1 && value.getString("apkSha256")==apkSha256)
                val hashes=value.getJSONObject("hashes")
                require(hashes.length()==names.size && names.all { hashes.getString(it).matches(Regex("[a-f0-9]{64}")) })
                require(value.getString("indexSha256")==indexHash(hashes))
            }
        }.getOrNull()
        if(cached!=null)return cached
        val hashes=JSONObject()
        for(name in names)hashes.put(name,open(name).use(::hash))
        val value=JSONObject().put("schemaVersion",1).put("apkSha256",apkSha256).put("hashes",hashes)
            .put("indexSha256",indexHash(hashes))
        files.mkdirs(root)
        val temporary=File(root,target.name+".writing")
        files.write(temporary,value.toString().toByteArray());files.sync(temporary);files.rename(temporary,target)
        return value
    }
    private fun indexHash(hashes: JSONObject): String {
        val canonical=hashes.keys().asSequence().toList().sorted().joinToString("\n") { "$it:${hashes.getString(it)}" }
        return hash(canonical.byteInputStream())
    }
    private fun hash(input: InputStream): String {
        val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(65536)
        while(true){val count=input.read(bytes);if(count<0)break;digest.update(bytes,0,count)}
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
