// StudioOne Mobile — root Gradle settings.
// Module map is documented in docs/ARCHITECTURE.md.

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StudioOneMobile"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// ── Core ─────────────────────────────────────────────────────────────────────
include(":app")
include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:ui")
include(":core:domain")
include(":core:data")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:realtime")
include(":core:audio")
include(":core:midi")
include(":core:analytics")

// ── Features ─────────────────────────────────────────────────────────────────
include(":feature:auth")
include(":feature:onboarding")
include(":feature:projects")
include(":feature:arranger")
include(":feature:mixer")
include(":feature:pianoroll")
include(":feature:instruments")
include(":feature:effects")
include(":feature:loops")
include(":feature:collab")
include(":feature:social")
include(":feature:export")
include(":feature:settings")
include(":feature:paywall")
