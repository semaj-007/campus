plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services) apply false
}

// Firebase remains optional for the offline demo. With the real project file in
// this module, Google Services generates the resources Firebase reads at startup.
if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.campus"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.campus"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    implementation("net.openid:appauth:0.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
