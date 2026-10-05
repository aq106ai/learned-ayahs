pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { mavenCentral() }
            filter {
                includeGroup("com.alphacephei")
                includeGroup("net.java.dev.jna")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "LearnedAyahsPlayer"
include(":app")
