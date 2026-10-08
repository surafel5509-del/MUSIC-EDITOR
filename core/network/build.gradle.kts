plugins {
    id("studioone.android.library")
    id("studioone.android.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.studioone.core.network"

    defaultConfig {
        // Values are injected from gradle.properties / local.properties at build time.
        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("studioone.supabase.url")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("studioone.supabase.anonKey")}\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    api(libs.supabase.postgrest)
    api(libs.supabase.storage)
    api(libs.supabase.realtime)
    api(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.okhttp.mockwebserver)
}
