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
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

val userHome: String = System.getProperty("user.home") ?: System.getenv("USERPROFILE") ?: "."
// DISABLED external build cache redirect (TRAE sandbox requires local build dirs)
// val BUILD_CACHE_ROOT: java.io.File = java.io.File(userHome, "android_build_cache/pcscan3").canonicalFile
// gradle.beforeProject {
//     val targetDir = java.io.File(BUILD_CACHE_ROOT, if (path == ":") "root_project" else path.replace(':', '_').trim('_'))
//         .resolve("build")
//     layout.buildDirectory.set(targetDir)
//     extensions.findByType<org.gradle.api.plugins.ExtraPropertiesExtension>()?.set("projectBuildDirOverride", targetDir.absolutePath)
// }

rootProject.name = "PC Scan"
include(":app")
