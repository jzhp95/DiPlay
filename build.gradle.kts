// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Use the same Build Tools for Android compilation, CI and APK packaging.
subprojects {
    val androidProject = this
    pluginManager.withPlugin("com.android.application") {
        androidProject.extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            androidResources.localeFilters.add("zh")
            androidResources.generateLocaleConfig = false
        }
    }
    listOf("com.android.application", "com.android.library").forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            androidProject.extensions.configure<com.android.build.api.dsl.CommonExtension> {
                buildToolsVersion = "36.1.0"
            }
        }
    }
}
