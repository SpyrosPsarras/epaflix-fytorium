const fs = require('node:fs');
const path = require('node:path');
const {
  AndroidConfig, withAndroidManifest, withAppBuildGradle, withDangerousMod, withGradleProperties, withMainActivity,
} = require('expo/config-plugins');

const firebaseComponents = [
  ['service', 'io.invertase.firebase.messaging.ReactNativeFirebaseMessagingService'],
  ['service', 'io.invertase.firebase.messaging.ReactNativeFirebaseMessagingHeadlessService'],
  ['receiver', 'io.invertase.firebase.messaging.ReactNativeFirebaseMessagingReceiver'],
  ['service', 'com.google.firebase.messaging.FirebaseMessagingService'],
];

const extractionRules = `<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup><exclude domain="root" path="." /><exclude domain="sharedpref" path="." /><exclude domain="file" path="." /><exclude domain="database" path="." /><exclude domain="external" path="." /></cloud-backup>
    <device-transfer><exclude domain="root" path="." /><exclude domain="sharedpref" path="." /><exclude domain="file" path="." /><exclude domain="database" path="." /><exclude domain="external" path="." /></device-transfer>
</data-extraction-rules>
`;

const debugManifest = `<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:networkSecurityConfig="@xml/network_security_config" /></manifest>
`;

const debugNetwork = `<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">app</domain>
        <domain includeSubdomains="false">oidc</domain>
        <domain includeSubdomains="false">fake-push</domain>
    </domain-config>
</network-security-config>
`;

const releaseGradle = `
    buildTypes.configureEach { buildType ->
        buildType.signingConfig = buildType.name == "debug" ? signingConfigs.debug : null
    }
`;

const releaseGuard = `
def validateFytoriumRelease = tasks.register("validateFytoriumRelease") {
    doLast {
        if (System.getenv("FYTORIUM_VARIANT") != "release") throw new GradleException("FYTORIUM_VARIANT=release is required for release builds")
        def mobile = rootDir.parentFile
        def process = new ProcessBuilder("npx", "expo", "config", "--type", "public", "--json").directory(mobile).redirectErrorStream(true).start()
        def output = process.inputStream.text
        if (process.waitFor() != 0) throw new GradleException("Release config refused: " + output.trim())
        def config = new groovy.json.JsonSlurper().parseText(output.substring(output.indexOf("{")))
        if (config.extra?.variant != "release" || !config.extra?.serverUrl?.startsWith("https://")) throw new GradleException("Release config is not an HTTPS release")
        def expected = mobile.toPath().resolve(System.getenv("GOOGLE_SERVICES_FILE")).toFile().text
        if (file("google-services.json").text != expected) throw new GradleException("android/app/google-services.json is stale; run expo prebuild again")
    }
}
tasks.configureEach { if (it.name == "preReleaseBuild") it.dependsOn(validateFytoriumRelease) }
`;

function write(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text);
}

function withFytoriumNative(config) {
  config = withAndroidManifest(config, (mod) => {
    const manifest = mod.modResults.manifest;
    manifest.$['xmlns:tools'] = 'http://schemas.android.com/tools';
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(mod.modResults);
    app.$['android:allowBackup'] = 'false';
    app.$['android:fullBackupContent'] = 'false';
    app.$['android:dataExtractionRules'] = '@xml/data_extraction_rules';
    app.$['android:usesCleartextTraffic'] = 'false';
    for (const [kind, name] of firebaseComponents) {
      app[kind] = (app[kind] || []).filter((entry) => entry.$['android:name'] !== name);
      app[kind].push({ $: { 'android:name': name, 'tools:node': 'remove' } });
    }
    return mod;
  });
  config = withDangerousMod(config, ['android', (mod) => {
    const appDir = path.join(mod.modRequest.platformProjectRoot, 'app', 'src');
    write(path.join(appDir, 'main', 'res', 'xml', 'data_extraction_rules.xml'), extractionRules);
    write(path.join(appDir, 'debug', 'AndroidManifest.xml'), debugManifest);
    write(path.join(appDir, 'debug', 'res', 'xml', 'network_security_config.xml'), debugNetwork);
    return mod;
  }]);
  config = withMainActivity(config, (mod) => {
    const marker = 'com.epaflix.fytorium.platform.RestoreState.created(this, savedInstanceState)';
    if (!mod.modResults.contents.includes(marker)) {
      mod.modResults.contents = mod.modResults.contents
        .replace(/(override fun onCreate\(savedInstanceState: Bundle\?\) \{\n)/, `$1    ${marker}\n    com.epaflix.fytorium.platform.Redirects.capture(intent)\n    com.epaflix.fytorium.platform.BackState.attach(this)\n`)
        .replace(/(\n  override fun getMainComponentName)/, `\n  override fun onNewIntent(intent: android.content.Intent) {\n    com.epaflix.fytorium.platform.Redirects.capture(intent)\n    super.onNewIntent(intent)\n  }\n$1`)
        .replace(/super\.invokeDefaultOnBackPressed\(\)/g, 'com.epaflix.fytorium.platform.BackState.whileDefault { super.invokeDefaultOnBackPressed() }');
      if (!mod.modResults.contents.includes(marker) || !mod.modResults.contents.includes('override fun onNewIntent')
        || !mod.modResults.contents.includes('BackState.whileDefault')) throw new Error('MainActivity layout changed');
    }
    return mod;
  });
  config = withAppBuildGradle(config, (mod) => {
    let gradle = mod.modResults.contents;
    if (!gradle.includes('validateFytoriumRelease')) {
      gradle = gradle.replace(/^\s*signingConfig signingConfigs\.debug\s*$/gm, '');
      gradle = gradle.replace(/\n    buildTypes \{/, `${releaseGradle}\n    buildTypes {`);
      gradle = gradle.replace(/\nreact \{\n/, '\nreact {\n    debuggableVariants = []\n');
      gradle += releaseGuard;
      if (!gradle.includes('buildType.signingConfig') || !gradle.includes('debuggableVariants = []')) throw new Error('app/build.gradle layout changed');
    }
    mod.modResults.contents = gradle;
    return mod;
  });
  return withGradleProperties(config, (mod) => {
    const properties = {
      reactNativeArchitectures: process.env.FYTORIUM_ABIS || 'armeabi-v7a,arm64-v8a,x86_64',
      'org.gradle.jvmargs': '-Xmx3g -XX:MaxMetaspaceSize=768m',
      'kotlin.daemon.jvmargs': '-Xmx1536m',
      'org.gradle.workers.max': '3',
    };
    mod.modResults = mod.modResults.filter((item) => !(item.key in properties));
    for (const [key, value] of Object.entries(properties)) mod.modResults.push({ type: 'property', key, value });
    return mod;
  });
};

module.exports = withFytoriumNative;
module.exports.debugNetwork = debugNetwork;
