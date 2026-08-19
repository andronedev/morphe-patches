plugins {
    id("com.android.application") version "8.7.3"
}

android {
    namespace = "app.morphe.lbc"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.morphe.lbc"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // Aucune obfuscation : l'injecteur et les plugins résolvent des noms par réflexion.
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Backend de hook ART sans root (même approche qu'Aliucord).
    compileOnly("com.github.canyie:pine:v0.3.0")
    implementation("com.github.canyie:pine:v0.3.0")
}

/**
 * Extrait `classes.dex` de l'APK de debug : c'est ce fichier qu'on pousse sur l'appareil,
 * dans `Android/data/fr.leboncoin/files/morphe/runtime.dex`.
 */
tasks.register<Copy>("extractRuntimeDex") {
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/leboncoin-debug.apk"))) {
        include("classes.dex")
    }
    into(layout.buildDirectory.dir("runtime"))
    rename { "runtime.dex" }
}
