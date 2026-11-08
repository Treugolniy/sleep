plugins {
    id("com.android.application")
}

android {
    namespace = "com.treugolniy.sleepjournal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.treugolniy.sleepjournal"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            // The web folder is the single source of truth for both the PWA and Android WebView.
            assets.srcDir("../web")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
    testImplementation("junit:junit:4.13.2")
}
