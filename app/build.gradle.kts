plugins {
    id("com.android.application")
}

android {
    namespace = "com.wooju.cellraillogger"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wooju.cellraillogger"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
