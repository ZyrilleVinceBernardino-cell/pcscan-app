// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.3" apply false
}

import java.io.File

// DISABLED external build cache redirect (TRAE sandbox requires local build dirs)
// val userHome: String = System.getProperty("user.home") ?: System.getenv("USERPROFILE") ?: "."
// val BUILD_CACHE_ROOT: File = File(userHome, "android_build_cache/pcscan3").canonicalFile
//
// gradle.beforeProject {
//     val projectPath = path
//     val folderName = if (projectPath == ":") "root_project" else projectPath.replace(':', '_').trim('_')
//     val targetDir = File(BUILD_CACHE_ROOT, folderName).resolve("build")
//     layout.buildDirectory.set(targetDir)
// }

subprojects {
    // DISABLED external build cache redirect (TRAE sandbox requires local build dirs)
    // val folderName = if (path == ":") "root_project" else path.replace(':', '_').trim('_')
    // val targetDir = File(BUILD_CACHE_ROOT, folderName).resolve("build")
    // layout.buildDirectory.set(targetDir)
    tasks.withType<Task>().configureEach { /* forces task container init */ }
    if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
        tasks.register("prepareKotlinBuildScriptModel") {
            group = "ide"
            description = "IDE sync hook -- ensures Kotlin DSL model classpath is resolved for subprojects"
        }
    }
}
