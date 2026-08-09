plugins {
    id("com.android.application")
}

android {
    namespace = "com.grxt.wsproxy"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.grxt.wsproxy"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
