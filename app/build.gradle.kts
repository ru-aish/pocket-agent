plugins { id("com.android.application") }
val ngrokNativeClasses = configurations.create("ngrokNativeClasses") { isTransitive = false }
val ngrokAndroidArm64 = configurations.create("ngrokAndroidArm64") { isTransitive = false }
val extractNgrokArm64 = tasks.register<Copy>("extractNgrokArm64") {
    from({ ngrokAndroidArm64.map { zipTree(it) } }) { include("libngrok_java.so") }
    into(layout.buildDirectory.dir("generated/ngrok-jni/arm64-v8a"))
}
val androidNgrokJar = tasks.register<Jar>("androidNgrokJar") {
    archiveBaseName.set("ngrok-native-android")
    destinationDirectory.set(layout.buildDirectory.dir("ngrok-classes"))
    from({ ngrokNativeClasses.map { zipTree(it) } })
    exclude("com/ngrok/Runtime*.class")
}
android {
    namespace = "dev.pocket.agent"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.pocket.agent"
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        versionName = "0.1.9-voice-continuity.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "DEVELOPMENT_TOOLS", "false")
    }
    buildFeatures { buildConfig = true; aidl = true }
    signingConfigs {
        create("distribution") {
            storeFile = file(System.getenv("APK_KEYSTORE") ?: "missing-keystore.jks")
            storePassword = System.getenv("APK_STORE_PASSWORD")
            keyAlias = System.getenv("APK_KEY_ALIAS") ?: "pocket-agent"
            keyPassword = System.getenv("APK_KEY_PASSWORD")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        release {
            buildConfigField("boolean", "DEVELOPMENT_TOOLS", "false")
            testProguardFiles.add(file("test-proguard-rules.pro"))
            signingConfig = signingConfigs.getByName("distribution")
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("development") {
            initWith(getByName("release"))
            buildConfigField("boolean", "DEVELOPMENT_TOOLS", "true")
            versionNameSuffix = "-dev"
            proguardFiles(file("src/development/proguard-rules.pro"))
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("development").jniLibs.srcDir(layout.buildDirectory.dir("generated/ngrok-jni").get().asFile)
    splits { abi { isEnable=true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk=true } }
    testBuildType = if (providers.gradleProperty("devEmulatorTest").isPresent) "development" else if (providers.gradleProperty("voiceEmulatorTest").isPresent) "debug" else "release"
    lint { textReport=true; textOutput=file("build/reports/lint-results-release.txt") }
    packaging { jniLibs { useLegacyPackaging = true }; resources { excludes += "META-INF/DEPENDENCIES" } }
}
dependencies {
    implementation(project(":android-platform"))
    implementation("com.caoccao.javet:javet-node-android-i18n:6.0.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    add("developmentImplementation", "org.nanohttpd:nanohttpd:2.3.1")
    add("developmentImplementation", "com.ngrok:ngrok-java:1.1.1")
    add("ngrokNativeClasses", "com.ngrok:ngrok-java-native:1.1.1")
    add("ngrokAndroidArm64", "com.ngrok:ngrok-java-native:1.1.1:linux-android-aarch_64")
    add("developmentImplementation", files(androidNgrokJar))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.10")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
}
tasks.matching { it.name == "mergeDevelopmentJniLibFolders" }.configureEach {
    dependsOn(extractNgrokArm64)
}
