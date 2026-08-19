// Build Gradle indépendant : le runtime n'est PAS un module de morphe-patches.
// Il produit un dex chargé à l'exécution par l'APK patché, il ne doit donc pas
// entrer dans le classpath des patchs ni casser `:patches:buildAndroid`.
rootProject.name = "morphe-lbc-runtime"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
