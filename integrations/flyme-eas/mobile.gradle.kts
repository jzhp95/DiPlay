import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Derive from upstream main Manifest; retain the original debug/release overlays. */
abstract class GenerateFlymeManifest : DefaultTask() {
    @get:InputFile abstract val sourceManifest: RegularFileProperty
    @get:OutputFile abstract val outputManifest: RegularFileProperty

    @TaskAction fun generate() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(sourceManifest.get().asFile)
        val android = "http://schemas.android.com/apk/res/android"
        val root = document.documentElement
        check(root.getAttributeNS(android, "sharedUserId").let { it.isEmpty() || it == "android.uid.system" })
        root.setAttributeNS(android, "android:sharedUserId", "android.uid.system")
        val application = document.getElementsByTagName("application").item(0)
            ?: error("Main Manifest must contain an application")
        val metadata = document.createElement("meta-data")
        metadata.setAttributeNS(android, "android:name", "com.shilapi.flymeeas.ENABLED")
        metadata.setAttributeNS(android, "android:value", "true")
        application.appendChild(metadata)
        val output = outputManifest.get().asFile
        output.parentFile.mkdirs()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(output))
    }
}

// Deliberately opt-in: ordinary builds retain upstream UID and application ID.
if (providers.gradleProperty("flymeSystem").orNull == "true") {
    val original = layout.projectDirectory.file("src/main/AndroidManifest.xml")
    val generateManifest = tasks.register<GenerateFlymeManifest>("generateFlymeMainManifest") {
        sourceManifest.set(original)
        outputManifest.set(layout.buildDirectory.file("generated/flyme-eas/AndroidManifest.xml"))
    }
    // Applied scripts do not inherit the Android plugin's classpath. Export only
    // a Gradle provider; the plugin-owning mobile script configures Android.
    extra["flymeEasMainManifest"] = generateManifest.flatMap { it.outputManifest }
    tasks.named("preBuild") { dependsOn(generateManifest) }
}
