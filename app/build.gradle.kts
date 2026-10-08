plugins {
    id("studioone.android.application")
    id("studioone.android.compose")
    id("studioone.android.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.studioone.mobile"

    defaultConfig {
        applicationId = "com.studioone.mobile"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            // Proguard rules ship in proguard-rules.pro (native + keep rules).
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.network)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.audio)
    implementation(projects.midi)
    implementation(projects.feature.home)
    implementation(projects.feature.editor)
    implementation(projects.feature.recorder)
    implementation(projects.feature.pianoroll)
    implementation(projects.feature.mixer)
    implementation(projects.feature.instruments)
    implementation(projects.feature.effects)
    implementation(projects.feature.library)
    implementation(projects.feature.collab)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.splashscreen)
    implementation(libs.bundles.lifecycle)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.lifecycle.process)

    testImplementation(libs.bundles.testing.unit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
}
