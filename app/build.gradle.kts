// `java.util` is shadowed by Gradle's `java` extension inside the script body.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xterra.helm"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.xterra.helm"
        minSdk = 31            // Edge2 ships Android 13/14 images
        targetSdk = 34
        versionCode = 1
        versionName = "0.9.0"
        // Inject Maps key from local.properties if present
        val props = Properties()
        val lp = rootProject.file("local.properties")
        if (lp.exists()) props.load(lp.inputStream())
        manifestPlaceholders["MAPS_API_KEY"] = props.getProperty("MAPS_API_KEY", "")

        // Shared API auth token from the gitignored secret.properties. Empty
        // when the file is absent → ApiServer leaves /api/* open (dev builds
        // don't lock themselves out), and logs a warning. Companion must hold
        // the SAME value.
        val secret = Properties()
        val sp = rootProject.file("secret.properties")
        if (sp.exists()) secret.load(sp.inputStream())
        buildConfigField("String", "API_TOKEN",
            "\"${secret.getProperty("HELM_API_TOKEN", "")}\"")

        // Edge2 is arm64-only; also strips LibVLC's other ABIs (~4× APK cut).
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Video: RTSP (Viofo IP cams) — Media3 for general use.
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.0")
    implementation("androidx.media3:media3-ui:1.4.0")
    // LibVLC gives materially lower glass-to-glass latency for the reverse cam.
    implementation("org.videolan.android:libvlc-all:3.6.0")

    // USB serial (ELM327 / OBD2 dongles, CH34x/FTDI/CP210x/PL2303)
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")

    // USB UVC (thermal camera). Jitpack lib wrapping libuvc:
    // TODO(thermal): JitPack's 3.3.3 publication is broken — libuvc has no POM,
    // libnative has a POM but no AAR. Nothing imports it yet (ThermalView is a
    // placeholder); re-add with verified coordinates when thermal work starts.
    // implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3")

    // Nav map: MapLibre Native (free/keyless tiles, 3D terrain, offline cache,
    // GeoJSON POI layers). Replaces the Google Maps SDK embed.
    implementation("org.maplibre.gl:android-sdk:11.13.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Spotify App Remote: download spotify-app-remote-release-x.y.z.aar from
    // https://github.com/spotify/android-sdk/releases into app/libs/
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.10.1") // required by app-remote

    // Settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // SSH/SFTP for backing waypoints up to the Unraid server (maintained
    // JSch fork with modern crypto).
    implementation("com.github.mwiede:jsch:0.2.21")

    // Starlink dish gRPC (h2c prior-knowledge unary calls to 192.168.100.1).
    // Local-LAN only — no external endpoints.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR generation for the companion-app download link (pure-Java, offline).
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
