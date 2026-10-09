plugins {
    id("studioone.android.library")
    id("studioone.android.room")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.studioone.core.database"
}

dependencies {
    implementation(projects.core.common)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    // RoomDatabase is a supertype of StudioOneDatabase; expose Room so that
    // downstream modules (core:data DI) can see it on their compile classpath.
    api(libs.room.runtime)
    api(libs.room.ktx)
    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.room.testing)
}
