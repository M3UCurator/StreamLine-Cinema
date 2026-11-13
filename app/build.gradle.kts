import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Release artifacts are named by build day and iteration (Cinema<ddMMyy>v<iteration>.apk)
// so successive builds never overwrite each other. Bump -PcinemaIteration per release;
// -PallAbis=true adds the emulator suffix and drops the 32-bit ABI filter for that build.
val iteration = providers.gradleProperty("cinemaIteration").orElse("12.84").get()
val allAbis = providers.gradleProperty("allAbis").map(String::toBoolean).getOrElse(false)
val buildDay = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyy"))
val artifactName = "Cinema${buildDay}v$iteration" + if (allAbis) "-allabis" else ""
// Release tags are v<iteration>; keep versionCode monotonic so the installer
// accepts an in-app update over the previous build.
val versionCodeValue = iteration.split('.').mapNotNull(String::toIntOrNull).let { parts ->
    if (parts.isEmpty()) 1 else parts[0] * 10_000 + (parts.getOrNull(1) ?: 0)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.streamline.cinema"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.streamline.cinema"
        minSdk = 23
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = iteration

        // The updater compares the installed iteration with the latest GitHub
        // release tag and downloads the same ABI flavor that is installed.
        buildConfigField("String", "ITERATION", "\"$iteration\"")
        buildConfigField("boolean", "ALL_ABIS", allAbis.toString())

        if (!allAbis) {
            ndk {
                abiFilters += "armeabi-v7a"
            }
        }
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        create("release") {
            // Debug keystore keeps release sideload builds installable and
            // update-compatible with existing debug installs.
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    testOptions {
        // Proxy tests exercise logging paths without an Android runtime.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
        jniLibs {
            // libVLC and libmpv each ship their own libc++_shared; both are the
            // same upstream runtime, so one copy is enough for the APK.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

val copyReleaseArtifact by tasks.registering {
    description = "Copies the signed release APK to the workspace root as $artifactName.apk"
    group = "build"
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val destination = rootProject.layout.projectDirectory.file("$artifactName.apk")
    doLast {
        val apk = apkDir.get().asFile.listFiles()
            ?.singleOrNull { it.isFile && it.extension == "apk" }
            ?: error("Expected exactly one release APK in ${apkDir.get()}")
        apk.copyTo(destination.asFile, overwrite = true)
        println("Named release artifact: ${destination.asFile}")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyReleaseArtifact)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-gson:2.12.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    // Jellyfin's FFmpeg extension: AC-3, E-AC-3, DTS/TrueHD and HEVC/AV1 software
    // decoding. DefaultRenderersFactory loads androidx.media3.decoder.ffmpeg
    // reflectively, so this AAR is the only FFmpeg copy on the classpath. Its
    // decoders have not changed API since Media3 1.9; the 1.11.1 modules above
    // win dependency resolution and stay source-compatible with these classes.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    // Additional playback engines behind the same Media3 facade. libVLC is the
    // packet-loss recovery profile; libmpv drives mpv directly for containers
    // and streams Media3 refuses. Both bundle all four Android ABIs.
    implementation("org.videolan.android:libvlc-all:3.7.7")
    implementation("dev.jdtech.mpv:libmpv:1.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
