plugins {
    id("studioone.android.library")
}

android {
    namespace = "com.studioone.audio"
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DOBOE_BUILD_EXAMPLES=OFF",
                    "-DOBOE_BUILD_TESTS=OFF",
                )
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    testImplementation(libs.bundles.testing.unit)
}
