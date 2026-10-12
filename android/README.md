# Fytorium Android

Kotlin and Compose phone app, API 29 through 35. This ticket provides login and an empty Today, Plants, Journal and More shell. No analytics, external crash service, offline cache or notification SDK is included.

## Run from the repository root

All compilation and tests run in Docker. Only Docker, its Compose plugin and the existing shell scripts are needed on the host.

```sh
./scripts/android build
./scripts/android test
./scripts/android lint
./scripts/android screenshots
./scripts/android release-check
./scripts/container install --frozen-lockfile
./scripts/stack up
./scripts/android e2e
./scripts/android release-launch
./scripts/stack down
```

The SDK image uses JDK 17.0.14+7 with a digest-pinned base, command-line tools 12.0, platform 35 revision 2, build-tools 35.0.0, and platform-tools 37.0.1. The emulator target adds emulator 37.2.12 and Google APIs API 35 x86_64 revision 9. Archive checksums are checked during the build. Package metadata for the emulator is checked in because command-line tools 12 does not discover its archive from source.properties alone.

Gradle 8.11.1 uses a checked-in wrapper and distribution SHA-256. Every direct plugin and dependency version is in `gradle/libs.versions.toml`. Gradle caches live in `android/.gradle-cache/` and are gitignored. Docker writes no Android installation to the host. The emulator image is not needed by CI.

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Test, lint and emulator reports are under `android/app/build/reports/`. A debug-only network security config permits cleartext HTTP only for the exact local hosts `app` and `oidc`, without subdomains. Its base policy and the release manifest forbid cleartext. Production defaults to `https://fytorium.epaflix.com`; the Gradle property `serverUrl` overrides this build setting. Release builds reject non-HTTPS URLs before compilation. `release-check` assembles an unsigned R8-minified, resource-shrunk APK and reports its size. Compose, AndroidX, coroutines and OkHttp supply their consumer keep rules; app models use direct Kotlin access and built-in JSON, with no reflective serializer to keep. `release-launch` requires the running stack and installs the release APK using an ephemeral container-only signing key, checks the login UI and process, and rejects crashes. The e2e script builds with `http://app:3000`. Ordinary build/test/lint commands reset it to production.

## Emulator networking and ownership

The emulator container joins this worktree's Compose network. Inside the container, dnsmasq forwards guest DNS to Docker's resolver at 127.0.0.11. The guest emulator is started with that container's IP as its DNS server. It can therefore reach both canonical origins, `http://app:3000` and `http://oidc:8080`, without published ports, changed OIDC callbacks or host DNS edits. An instrumented test asserts both endpoints respond from the guest, not merely from its container.

`./scripts/android e2e` requires a running stack from `./scripts/stack up`. It boots a fresh AVD with `--device /dev/kvm`, installs the debug APK and runs instrumented tests. The container runs as the caller's UID with the KVM device group, so caches remain caller-owned. The AVD is ephemeral, so the second run has no old browser cookies or device credentials. Boot waits are bounded. The emulator container has a unique name and is removed on exit, including failure. INT and TERM exit through cleanup. The stack is never started, rebuilt or stopped by e2e; it stays running for the next run.

To run twice on exactly the same stack:

```
./scripts/container install --frozen-lockfile
./scripts/stack up
./scripts/android e2e
./scripts/android e2e
./scripts/stack down
```

The launch, guest networking and real Android Keystore round-trip tests run now. The Custom Tab scenario is present but uses a JUnit assumption until A01 supplies app login endpoints. After merging A01, run `E2E_LOGIN=1 ./scripts/android e2e` twice on an empty synthetic stack. It enters subject `synthetic-a03-owner` in the real OIDC provider, completes the redirect and asserts empty Today. No mocked token endpoint replaces the server. The Google APIs image supplies Chrome for Custom Tabs. The emulator stays on homePC, not GitHub CI.

## Login and diagnostics

Login opens `/auth/login` with `app=android`, the fixed `com.epaflix.fytorium:/oauth2redirect` URI, S256 challenge and a fresh 256-bit state. The app checks the exact hostless URI, state, duplicate query parameters and a ten-minute local login-attempt expiry. A validated redirect consumes the attempt before exchanging a code. Invalid or stale redirects cannot consume a newer pending attempt. Redirects received after login are ignored with a predefined debug-only logcat event, not a user error. The server's one-time code expiry remains 60 seconds.

Both tokens and the pending verifier/state are AES-256-GCM encrypted with an Android Keystore key. Pending state survives process recreation. No backup or device transfer may carry credential files. All API requests use a bearer token when logged in. The Application owns the API client, credential store and SessionController. SessionController exposes loggedIn, busy, message and updateRequired as a StateFlow, deriving loggedIn only from the credential store token flow. Login completion, version checks, refresh and logout run in its process-scoped coroutine scope. Its redirect queue and in-flight exchanges survive Activity recreation; Activities only forward fresh intents and render session state. A process-wide lock serializes refreshes across clients, re-reads the store, and reuses tokens already rotated by another caller. A 401 triggers at most one refresh and one replay. Only an explicit `invalid_grant` or `invalid_token` refresh rejection clears credentials and returns to login. Rate limits, server failures, malformed responses and network errors retain tokens and show a retry message. A retry sends the same stored refresh token after a lost reply, matching login contract Amendment 1. Restored Activities skip the old redirect intent; fresh redirects still run through `onNewIntent`. Logout calls the server and clears local credentials even if the network fails. There is no local domain cache yet.

At startup, and again after login, `/api/meta` gates on 426 or a newer semantic `minAppVersion`. A missing endpoint, 404, is compatible until A02. The request sends `X-App-Version`. Network failures show a retry message instead of an update requirement.

More → About shows version 1.0.0 and copies at most 64 predefined error events, bounded to 8 KiB. Exceptions, intent URLs, response bodies and user input cannot enter that log. The crash handler records only APP_CRASH and terminates without logging throwable text. No tokens, authorization codes or verifiers enter diagnostics.

## Theme and tests

This base has no `src/app.css`. The palette uses the actual web layout colours and manifest: background #fff7ed, text #292524, accessible interactive orange #9a3412, brand orange #ea580c. Dark mode uses warm stone surfaces and lighter orange. The system setting chooses the palette; dynamic device colours are intentionally disabled. White text on #ea580c fails WCAG AA, so text buttons use the web's darker orange.

MockWebServer tests cover RFC 7636 PKCE, state/URI rejection, contract exchange, single-use state, refresh rotation, concurrent requests, explicit refresh rejection, retryable failures, second 401, logout and version checks. Source-policy assertions and device tests restrict debug cleartext; Activity recreation tests check process-owned client/store identity, queued redirects during a blocked version check, and token responses arriving after recreation. Duplicate and stale-then-valid redirects are covered, as is the process-restore replay guard. Robolectric tests exercise every shell screen with Compose accessibility checks and explicit 48 dp target/4.5:1 contrast assertions.

Fourteen checked-in Roborazzi reference PNGs cover Login, Update, Today, Plants, Journal, More and About at 360×800 in both themes. `screenshots` verifies them. Deliberate UI changes update references with `ANDROID_RECORD_SCREENSHOTS=1 ./scripts/android screenshots`. Review the changed PNGs before staging. All generated test outputs stay in ignored build directories.

## Dependency choices

All entries below are pinned in the version catalog. Android's built-in Keystore, clipboard and JSON API avoid extra storage and serialization dependencies.

| Artifact | Why it is here |
| --- | --- |
| Android Gradle plugin | APK packaging, SDK integration and lint |
| Kotlin Android and Compose compiler plugins | Kotlin source and Compose compilation |
| activity-compose | ComponentActivity and Compose lifecycle integration |
| compose-ui | Rendering, semantics and layout primitives |
| compose-foundation | Layout, scrolling and system theme detection |
| material3 | Accessible Material 3 shell, buttons and bottom navigation |
| material-icons-extended | Eco and Book are absent from material-icons-core. Extended supplies those bottom-bar icons and includes core for Check and Menu. It is large, but no custom icon assets or hand-drawn replacements are needed. |
| browser | Custom Tabs, not an embedded WebView |
| kotlinx-coroutines-android | Process-owned session work off the UI thread and StateFlow updates |
| okhttp | Bounded HTTP calls and the API client; no HTTP logging interceptor |
| junit | JVM assertions, test runner and conditional pending scenario |
| mockwebserver | Synthetic HTTP exchange and refresh tests |
| robolectric | Android screen/JSON execution on the JVM |
| ui-test-junit4 | Compose screen actions and assertions on JVM and emulator |
| ui-test-junit4-accessibility | Automated Android accessibility checks on screen actions |
| ui-test-manifest | Test-only Compose host activity |
| Roborazzi plugin and roborazzi | Native-graphics screenshots and reference verification |
| androidx.test runner and ext junit | Device instrumentation runner and AndroidJUnit4 |
| uiautomator | Drive the real browser/OIDC provider outside the app process |
