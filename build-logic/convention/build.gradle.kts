plugins {
    `kotlin-dsl`
}

group = "com.studioone.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.7.3")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    compileOnly("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.0.21-1.0.28")
    compileOnly("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "studioone.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "studioone.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "studioone.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "studioone.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "studioone.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidJniLibrary") {
            id = "studioone.android.jni.library"
            implementationClass = "AndroidJniLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "studioone.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
