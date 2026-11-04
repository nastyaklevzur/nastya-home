import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Вшитый fallback: movies.json, cams.json, постеры и превью камер из корня репозитория попадают в assets при сборке.
val bundledAssets = layout.buildDirectory.dir("generated/bundledAssets")
val syncBundledAssets by tasks.registering(Sync::class) {
    from(rootDir.resolve("movies.json"))
    from(rootDir.resolve("cams.json"))
    from(rootDir.resolve("posters")) { into("posters") }
    from(rootDir.resolve("cams")) { into("cams") }
    into(bundledAssets)
}

// Токен GitHub для отправки state.json (fine-grained, только Contents этого репозитория).
// Лежит в local.properties (в .gitignore): github.token=github_pat_...  Нет токена — синхронизация выключена.
val githubToken: String = Properties().let { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    props.getProperty("github.token", "").trim()
}

android {
    namespace = "ru.nastya.home"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.nastya.home"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "1.0"
        ndk { abiFilters += listOf("armeabi-v7a") }
        resourceConfigurations += listOf("ru")
        buildConfigField("String", "GITHUB_TOKEN", "\"$githubToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir(bundledAssets)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") { dependsOn(syncBundledAssets) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
