// Sample: how a launcher embeds DiPlay's live dashboard map. See docs/LAUNCHER_INTEGRATION.md.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diplay.maphost"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.diplay.maphost"
        minSdk = 30 // SurfaceView.getHostToken and setChildSurfacePackage
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Compile the same presentation sources without depending on CarPlay services.
androidComponents {
    onVariants { variant ->
        variant.sources.java?.addStaticSourceDirectory(rootProject.file("ui-style/src/main/java").absolutePath)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
