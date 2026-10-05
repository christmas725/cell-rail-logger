plugins {
    id("com.android.application")
}

android {
    namespace = "com.wooju.cellraillogger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wooju.cellraillogger"
        minSdk = 31
        targetSdk = 36
        versionCode = 8
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
