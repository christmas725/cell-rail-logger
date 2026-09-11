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
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
