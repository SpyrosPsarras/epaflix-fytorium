import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.roborazzi)
}
val releaseTag = providers.environmentVariable("ANDROID_RELEASE_TAG").orNull
val releaseVersion = releaseTag?.let { tag ->
    providers.exec {
        commandLine("sh", rootProject.file("docker/release-version").absolutePath, tag)
    }.standardOutput.asText.get().lineSequence().filter { it.contains('=') }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
}
// FirebaseInitProvider reads the same resources produced by the Google Services plugin.
// Parse the CI secret in memory instead of writing a real google-services.json to the checkout.
val googleSecret = providers.environmentVariable("GOOGLE_SERVICES_JSON").orNull
fun firebaseResources(json: String): Map<String, String> {
    val root = JsonSlurper().parseText(json) as Map<*, *>
    val project = root["project_info"] as Map<*, *>
    val client = (root["client"] as List<*>).map { it as Map<*, *> }.single {
        val info = it["client_info"] as Map<*, *>
        (info["android_client_info"] as Map<*, *>)["package_name"] == "com.epaflix.fytorium"
    }
    return mapOf("google_app_id" to (client["client_info"] as Map<*, *>)["mobilesdk_app_id"].toString(),
        "gcm_defaultSenderId" to project["project_number"].toString(), "project_id" to project["project_id"].toString(),
        "google_api_key" to ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"].toString())
        .also { require(it.values.none { value -> value.isBlank() || value == "null" }) { "Invalid Firebase config" } }
}
val syntheticFirebase = firebaseResources(file("src/debug/google-services.json").readText())
val releaseFirebase = googleSecret?.let { try { firebaseResources(it) } catch (_: Exception) { error("Invalid GOOGLE_SERVICES_JSON") } }
val validateFirebaseRelease = tasks.register("validateFirebaseRelease") {
    doLast {
        require(releaseFirebase != null) { "GOOGLE_SERVICES_JSON CI secret is required for release" }
        require(!releaseFirebase.getValue("project_id").startsWith("synthetic-") || providers.environmentVariable("ANDROID_FIREBASE_TEST_CONFIG").orNull == "true") { "Synthetic Firebase config cannot be released" }
    }
}
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val serverUrl = providers.gradleProperty("serverUrl").getOrElse("https://fytorium.epaflix.com")
val validateReleaseServerUrl = tasks.register("validateReleaseServerUrl") {
    doLast { require(serverUrl.startsWith("https://")) { "Release serverUrl must use https://" } }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseServerUrl, validateFirebaseRelease)
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
        debug {
            syntheticFirebase.forEach { (key, value) -> resValue("string", key, value) }
            resValue("bool", "firebase_auto_init", "false")
        }
        release {
            releaseFirebase?.forEach { (key, value) -> resValue("string", key, value) }
            resValue("bool", "firebase_auto_init", if (releaseFirebase?.get("project_id")?.startsWith("synthetic-") == true) "false" else "true")
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
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation(libs.firebase.messaging)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.icons)
    implementation(libs.browser)
    implementation(libs.coroutines)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    kapt(libs.room.compiler)
    implementation(libs.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
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
