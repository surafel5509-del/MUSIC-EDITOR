package com.studioone.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Convention for the :app module (application plugin, signing, splits, profiles). */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<ApplicationExtension> {
                namespace = StudioOne.APP_ID
                configureKotlinAndroid(this)

                defaultConfig {
                    applicationId = StudioOne.APP_ID
                    targetSdk = StudioOne.TARGET_SDK
                    versionCode = StudioOne.VERSION_CODE
                    versionName = StudioOne.VERSION_NAME
                    resourceConfigurations += listOf("en", "es", "fr", "de", "pt", "ja", "ko", "ar", "hi", "id", "tr", "it")
                }

                signingConfigs {
                    // Release signing reads from keystore.properties (gitignored).
                    // CI injects it from encrypted secrets; see .github/workflows/release.yml.
                    if (project.file("keystore.properties").exists()) {
                        create("release") {
                            val props = java.util.Properties().apply {
                                project.file("keystore.properties").inputStream().use(::load)
                            }
                            storeFile = project.file(props.getProperty("storeFile"))
                            storePassword = props.getProperty("storePassword")
                            keyAlias = props.getProperty("keyAlias")
                            keyPassword = props.getProperty("keyPassword")
                            enableV2Signing = true
                            enableV3Signing = true
                        }
                    }
                }

                buildTypes {
                    getByName("release") {
                        if (signingConfigs.findByName("release") != null) {
                            signingConfig = signingConfigs.getByName("release")
                        }
                    }
                    create("benchmark") {
                        initWith(getByName("release"))
                        signingConfig = signingConfigs.getByName("debug")
                        matchingFallbacks += listOf("release")
                        isDebuggable = false
                    }
                }

                // Split APKs by ABI: armeabi-v7a, arm64-v8a, x86_64 (emulator/Chromebook).
                splits {
                    abi {
                        isEnable = true
                        reset()
                        include("armeabi-v7a", "arm64-v8a", "x86_64")
                        isUniversalApk = true
                    }
                }
            }
        }
    }
}
