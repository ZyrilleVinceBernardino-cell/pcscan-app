import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val localProps = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}
val openAiKey: String = localProps.getProperty("OPENAI_API_KEY") ?: ""
val geminiKey: String = localProps.getProperty("GOOGLE_GEMINI_API_KEY") ?: ""

android {
    namespace = "com.pcscan.pcscan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pcscan.pcscan"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField(
            "String",
            "OPENAI_API_KEY",
            "\"${openAiKey.replace("\"", "\\\"")}\""
        )
        buildConfigField(
            "String",
            "GOOGLE_GEMINI_API_KEY",
            "\"${geminiKey.replace("\"", "\\\"")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// =====================================================
// R.jar LOCK PERMANENT FIX (Windows kernel minifilter hold)
// No BuildService required. Plain helper functions.
// 3 Layers: (1) RETRY delete helper
//           (2) PRE processDebugResources task doFirst
//           (3) POST buildFinished listener cleanup
// =====================================================

/**
 * Delete a list of files with exponential-backoff retry loop.
 * Windows kernel minifilters (OneDrive/Defender) sometimes hold R.jar open for
 * 0.5-10 seconds after previous build - we just wait and retry until released.
 */
fun deleteFilesWithRetry(paths: List<File>, maxAttempts: Int = 15, initialWaitMs: Long = 200L) {
    paths.forEach { target ->
        var attempt = 0
        var ok = false
        while (attempt < maxAttempts && !ok) {
            attempt++
            try {
                if (target.exists()) {
                    val deleted = target.delete()
                    if (deleted) {
                        println("[R.jar OK $attempt/$maxAttempts] deleted ${target.name}")
                        ok = true
                    } else {
                        val w = initialWaitMs * attempt
                        println("[R.jar RETRY $attempt/$maxAttempts] $target locked, wait ${w}ms")
                        Thread.sleep(w)
                    }
                } else {
                    ok = true
                }
            } catch (e: Throwable) {
                val w = initialWaitMs * attempt
                println("[R.jar LOCKED $attempt/$maxAttempts] ${e.message?.take(80)} wait ${w}ms")
                Thread.sleep(w)
            }
        }
        if (!ok) println("[R.jar GIVE UP] Still locked after $maxAttempts tries: $target")
    }
}

/** Collect R.jar files for a SINGLE variant only (never touch other variants). */
fun collectVariantRJars(buildDir: File, variant: String): List<File> {
    val out = mutableListOf<File>()
    try {
        listOf(
            "intermediates/compile_and_runtime_not_namespaced_r_class_jar",
            "intermediates/compile_and_runtime_r_class_jar"
        ).forEach { base ->
            val variantDir = File(buildDir, "$base/$variant")
            if (variantDir.exists() && variantDir.isDirectory) {
                variantDir.walkTopDown().forEach { f ->
                    if (f.isFile && f.name.equals("R.jar", ignoreCase = true)) out.add(f)
                }
            }
        }
    } catch (e: Throwable) {
        println("[R.jar SCAN SKIP] ${e.message?.take(80)}")
    }
    return out.distinctBy { it.absolutePath }
}

println("[R.jar] Build session=${System.currentTimeMillis()}")

// LAYER 1: RUN just BEFORE every processXxxResources task.
// This fires EVEN BEFORE AGP's own doFirst code that opens the exclusive
// R.jar handle - so we have a chance to delete stale files first.
// IMPORTANT: only the R.jar of the variant being built is removed. Deleting
// another variant's R.jar would break lintVital/dex tasks that expect it.
tasks.configureEach {
    val currentTaskName = name
    val variantName = when (currentTaskName) {
        "processDebugResources" -> "debug"
        "processReleaseResources" -> "release"
        else -> null
    }
    if (variantName != null) {
        doFirst {
            val bd = project.layout.buildDirectory.asFile.get()
            val list = collectVariantRJars(bd, variantName)
            if (list.isEmpty()) {
                println("[R.jar PRE $currentTaskName] No stale R.jar files found. Clean run.")
            } else {
                println("[R.jar PRE $currentTaskName] Found ${list.size} stale R.jar files -> running retry delete.")
                deleteFilesWithRetry(list, 15, 200L)
            }
        }
    }
}

// LAYER 2 (global post-build cleanup) intentionally removed: deleting every
// variant's R.jar after each build left valid outputs missing while Gradle
// still considered the resource task up-to-date, which broke release builds
// (lintVitalAnalyzeRelease / dexBuilderRelease: NoSuchFileException R.jar).
// LAYER 1 already releases the handle before AGP opens it.

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.3")
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.5")
    implementation("com.github.yalantis:ucrop:2.2.8-native")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
