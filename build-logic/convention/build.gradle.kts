plugins {
    `kotlin-dsl`
}

group = "com.studioone.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.serialization.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "studioone.android.application"
            implementationClass = "studioone.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "studioone.android.library"
            implementationClass = "studioone.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "studioone.android.compose"
            implementationClass = "studioone.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "studioone.android.hilt"
            implementationClass = "studioone.HiltConventionPlugin"
        }
        register("androidRoom") {
            id = "studioone.android.room"
            implementationClass = "studioone.RoomConventionPlugin"
        }
        register("androidFeature") {
            id = "studioone.android.feature"
            implementationClass = "studioone.FeatureConventionPlugin"
        }
    }
}
