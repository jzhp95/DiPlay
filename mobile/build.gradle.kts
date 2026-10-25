import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// User-provisioned local input; clean source/CI builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.takeIf { it.isNotBlank() }?.let { file(it).canonicalFile }
    ?: rootProject.projectDir.takeIf { it.resolve("offline-mfi").isDirectory }
val authenticationNames = listOf("identity.pk8", "certificate.p7b")
val selectedAuthenticationFiles = localAuthenticationAssets?.let { directory ->
    authenticationNames.map { directory.resolve("offline-mfi/$it").canonicalFile }
} ?: emptyList()
val generatedAuthenticationAssets = layout.buildDirectory.dir("generated/local-auth-assets")
val stageLocalAuthenticationAssets = localAuthenticationAssets?.let { directory ->
    tasks.register<Sync>("stageLocalAuthenticationAssets") {
        from(directory.resolve("offline-mfi")) {
            include(authenticationNames)
            into("offline-mfi")
        }
        into(generatedAuthenticationAssets)
        val inputsToStage = selectedAuthenticationFiles
        doFirst {
            check(inputsToStage.all { it.isFile && it.length() in 1L..16384L }) {
                "Local CarPlay authentication files must exist and contain 1..16384 bytes each"
            }
        }
    }
}

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 28
        targetSdk = 37
        versionCode = 43
        versionName = "0.2.24"

    }


    if (localAuthenticationAssets != null) {
        sourceSets.getByName("main").assets.directories.add(generatedAuthenticationAssets.get().asFile.absolutePath)
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".hudtest"
            versionNameSuffix = "-hud-test"
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Only the two staged runtime files may enter APK assets; never add the repository root.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let {
        authenticationNames.map {
            generatedAuthenticationAssets.get().asFile.resolve("offline-mfi/$it").canonicalFile
        }.toSet()
    } ?: emptySet()
    stageLocalAuthenticationAssets?.let { dependsOn(it) }
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must contain the user-provisioned local authentication input.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require root offline-mfi/ or DIPLAY_AUTH_ASSETS_DIR."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}

apply(from = rootProject.file("integrations/flyme-eas/mobile.gradle.kts"))
if (providers.gradleProperty("flymeSystem").orNull == "true") {
    val flymeManifest = checkNotNull(extra["flymeEasMainManifest"]) {
        "Flyme EAS build script did not export its Manifest provider"
    }
    android {
        defaultConfig { applicationId = "com.shihab.diplay.flyme" }
        sourceSets.getByName("main").manifest.srcFile(flymeManifest)
    }
}
