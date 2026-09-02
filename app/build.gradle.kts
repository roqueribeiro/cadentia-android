import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.levelhard.cadentia"
    compileSdk = 36
    // Fixado no que o CI compila hoje; sem isto o AGP escolhe (e baixa) outro.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.levelhard.cadentia"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // O motor de áudio (Oboe) é C++. arm64 e x86_64 cobrem aparelho e
            // emulador; sem armeabi-v7a, que já não vale o peso.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // O AAR do Oboe é compilado contra a STL compartilhada; sem
                // isto o prefab rejeita a biblioteca (CXX1212).
                arguments += "-DANDROID_STL=c++_shared"
                // Páginas de 16 KB: o NDK 27 ainda linka a 4 KB por padrão e o
                // Android 15+ (e a Play) exigem LOAD alinhado a 16 KB. Achado
                // no emulador com imagem ps16k: "This app isn't 16 KB
                // compatible" apontando libcadentia_audio.so.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    // A Play só aceita AAB assinado com a SUA chave. Credenciais em
    // keystore.properties (fora do git); sem o arquivo, release não assina, em
    // vez de assinar com a chave de debug e gerar um pacote que a Play recusa
    // lá na frente. (mesma regra do myabba-android)
    signingConfigs {
        create("release") {
            val props = Properties()
            val file = rootProject.file("keystore.properties")
            if (file.exists()) {
                props.load(file.inputStream())
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            val keystore = rootProject.file("keystore.properties")
            signingConfig = if (keystore.exists()) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Sufixo para conviver com o release no mesmo aparelho durante QA.
            applicationIdSuffix = ".debug"
        }
        // APK enxuto assinado com a chave de debug: o que dá para mandar por
        // canal com limite de tamanho e instalar com adb, sem keystore da Play.
        create("qa") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += "debug"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Oboe é distribuído como AAR com headers/libs via prefab; o CMake
        // acha por find_package(oboe CONFIG). Sem prefab, teria que compilar
        // o Oboe do fonte.
        prefab = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":kit"))

    // Motor de áudio de baixa latência (AAudio/OpenSL por baixo). AAR com
    // prefab: os headers e a .so entram no build C++ via find_package.
    implementation("com.google.oboe:oboe:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Gerador de QR local para o pareamento RoqueOS: JVM pura, ~500 KB. O
    // Android não tem o CIQRCodeGenerator do iOS, e mandar a URL de
    // pareamento para um serviço externo seria vazar o convite de acesso.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Cordas, modo câmera. O iOS usa o Vision (zero bytes no bundle); o Android
    // não tem equivalente do sistema, então entra o MediaPipe Hand Landmarker
    // (Apache-2.0, crédito na tela Sobre) com o modelo `hand_landmarker.task`
    // nos assets (7,8 MB). As .so do tasks-core são alinhadas a 16 KB
    // (conferido com readelf: LOAD align 0x4000).
    //
    // O tasks-core traz o datatransport da Google para mandar estatística de
    // uso ("COREML_ON_DEVICE_SOLUTIONS"), sem opção pública de desligar. O
    // manifesto remove o registro do backend CCT (ver AndroidManifest.xml), e
    // o runtime descarta os eventos com "Transport backend 'cct' is not
    // registered": nada sai do aparelho. Confira no logcat ao abrir a câmera.
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
