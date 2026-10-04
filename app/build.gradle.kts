plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "ru.florestdev.messenger"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.florestdev.messenger"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-VIKTORIA"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // ANDROID
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // PUSH
    implementation(platform("com.google.firebase:firebase-bom:33.14.0"))

    // Добавляем библиотеку FCM (версия управляется через BoM)
    implementation("com.google.firebase:firebase-messaging")

    // (Опционально) Добавляем Firebase Analytics для аналитики пушей
    implementation("com.google.firebase:firebase-analytics")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Socket.IO
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }

    // JSON, HTML
    implementation("org.json:json:20240303")
    implementation("org.jsoup:jsoup:1.17.2")

    // Image loading (аватары)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.1.3")
}