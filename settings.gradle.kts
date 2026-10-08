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

include(":app")

include(":core:common")
include(":core:domain")
include(":core:data")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:designsystem")
include(":core:ui")

include(":audio")
include(":midi")

include(":feature:home")
include(":feature:editor")
include(":feature:recorder")
include(":feature:pianoroll")
include(":feature:mixer")
include(":feature:instruments")
include(":feature:effects")
include(":feature:library")
include(":feature:collab")
include(":feature:settings")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
