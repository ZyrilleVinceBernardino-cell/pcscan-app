// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.3" apply false
}

subprojects {
    tasks.withType<Task>().configureEach { /* forces task container init */ }
    if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
        tasks.register("prepareKotlinBuildScriptModel") {
            group = "ide"
            description = "IDE sync hook — ensures Kotlin DSL model classpath is resolved for subprojects"
        }
    }
}
