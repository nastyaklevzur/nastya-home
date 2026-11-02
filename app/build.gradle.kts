plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Вшитый fallback: movies.json и постеры из корня репозитория попадают в assets при сборке.
val bundledAssets = layout.buildDirectory.dir("generated/bundledAssets")
val syncBundledAssets by tasks.registering(Sync::class) {
    from(rootDir.resolve("movies.json"))
    from(rootDir.resolve("posters")) { into("posters") }
    into(bundledAssets)
}

android {
    namespace = "ru.nastya.home"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.nastya.home"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("armeabi-v7a") }
        resourceConfigurations += listOf("ru")
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.7.0")
}
