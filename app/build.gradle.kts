plugins {
    id("com.android.application")
}

android {
    namespace = "com.thinh.gshockgeo"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.thinh.gshockgeo"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
