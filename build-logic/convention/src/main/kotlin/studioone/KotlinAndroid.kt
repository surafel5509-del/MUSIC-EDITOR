package studioone

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Version catalog accessor for use inside convention plugins. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Shared Kotlin + Android configuration applied to every module:
 * compile/target SDK, JVM 17 bytecode, and strict compiler warnings.
 */
internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    val catalog = libs
    commonExtension.apply {
        compileSdk = catalog.findVersion("compileSdk").get().requiredVersion.toInt()

        defaultConfig {
            minSdk = catalog.findVersion("minSdk").get().requiredVersion.toInt()
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        testOptions {
            unitTests.isIncludeAndroidResources = true
            unitTests.isReturnDefaultValues = true
        }

        lint {
            warningsAsErrors = false
            abortOnError = true
            checkDependencies = true
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }
}

/** Adds the core libraries every Android module depends on. */
internal fun Project.configureCoreKtx() {
    dependencies {
        "implementation"(libs.findLibrary("androidx-core-ktx").get())
        "implementation"(libs.findLibrary("kotlinx-coroutines-android").get())
    }
}
