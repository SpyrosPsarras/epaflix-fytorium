plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}
val releaseTag = providers.environmentVariable("ANDROID_RELEASE_TAG").orNull
val releaseVersion = releaseTag?.let { tag ->
    providers.exec {
        commandLine("sh", rootProject.file("docker/release-version").absolutePath, tag)
    }.standardOutput.asText.get().lineSequence().filter { it.contains('=') }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
}
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val serverUrl = providers.gradleProperty("serverUrl").getOrElse("https://fytorium.epaflix.com")
val validateReleaseServerUrl = tasks.register("validateReleaseServerUrl") {
    doLast { require(serverUrl.startsWith("https://")) { "Release serverUrl must use https://" } }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseServerUrl)
}
android {
    namespace = "com.epaflix.fytorium"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.epaflix.fytorium"
        minSdk = 29
        targetSdk = 35
        versionCode = releaseVersion?.getValue("versionCode")?.toInt() ?: 1
        versionName = releaseVersion?.getValue("versionName") ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SERVER_URL", "\"" + serverUrl + "\"")
    }
    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storeType = "PKCS12"
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                    ?.takeIf { it.isNotBlank() } ?: error("ANDROID_KEYSTORE_PASSWORD is required")
                keyPassword = storePassword
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                    ?.takeIf { it.isNotBlank() } ?: error("ANDROID_KEY_ALIAS is required")
            }
        }
    }
    buildTypes {
        release {
            if (!releaseKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true; animationsDisabled = true }
}
dependencies {
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.icons)
    implementation(libs.browser)
    implementation(libs.coroutines)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.compose.test)
    testImplementation(libs.compose.accessibility)
    testImplementation(libs.roborazzi)
    debugImplementation(libs.compose.test.manifest)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.uiautomator)
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("roborazzi.output.dir", layout.buildDirectory.dir("outputs/roborazzi").get().asFile.path)
}
