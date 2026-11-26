# React Native migration

Replace the Kotlin/Compose app in `android/` with one TypeScript React Native app in `mobile/` for Android and iOS. Scope is parity with the current Android app plus what iOS itself requires; no new features. The SvelteKit server and web UI stay.

Behaviour references: `CONTEXT.md`, `android/README.md` and the W1–W6 specs in this folder, final amendments winning. Kotlin paths in old specs stay as history.

## Owner decisions (2026-11-24)

| Code | Decision |
| --- | --- |
| D1 | Android and iOS. |
| D2 | Existing Android installs upgrade in place and log in once more. The old FCM token is deleted before the new device registers. |
| D3 | Emulator e2e and native builds run in a disposable privileged namespace on the homelab cluster, up to 25 GiB node disk, deleted after the migration. |
| D4 | Maestro screenshots of key screens in light and dark go into each wave's PR. No pixel-diff gate. The 276 Roborazzi baselines go with the Kotlin app. |
| D5 | Expo with Continuous Native Generation (CNG) and a dev client. No Expo Go, EAS or Expo Push Service. |
| D6 | Council loops until every item scores 9.5; this replaces the three-round cap in `docs/agent-workflow.md` for this migration. |
| D7 | iOS ships through internal TestFlight testing only: each household member is an App Store Connect user in the owner's team, so there is no Beta App Review and no demo account. Needs the owner's Apple Developer Program membership; until then the iOS release job stays off. |
| D8 | The lab workspace is a 25 GiB `truenas-iscsi` volume, which enforces its size and uses no node disk. Its PV is retained, so it is deleted by hand with the namespace. |

## Stack

`mobile/` has its own pnpm lockfile (`node-linker=hoisted`, required by Expo) and pins Node 22 LTS in `.nvmrc` and the build image. `mobile/android` and `mobile/ios` are generated and gitignored. Native behaviour that Expo modules do not give lives in one local Expo module, `mobile/modules/fytorium-native` (Kotlin + Swift, N1–N10), and its config plugin.

| Concern | Choice |
| --- | --- |
| Framework | Expo SDK (latest stable at W1, pinned), New Architecture, TypeScript strict. |
| App identity | `android.package` and iOS bundle id `com.epaflix.fytorium`; scheme `com.epaflix.fytorium`; `expo-build-properties` `minSdkVersion` 29, `targetSdkVersion` 35; `allowBackup: false` and the current `data_extraction_rules.xml` (cloud backup and device transfer exclude everything) through the config plugin; Photo Picker backport metadata from `AndroidManifest.xml:20-23`. Release APK inspection asserts all of them. |
| Navigation | `@react-navigation/bottom-tabs` + `native-stack`; `predictiveBackGestureEnabled`. No URL deep links, so no expo-router. |
| UI | `react-native-paper` (MD3), `@expo/vector-icons`, `@react-native-community/datetimepicker`. Palette from `Theme.kt`, system light/dark. |
| Credentials | `expo-secure-store`. The token pair uses `AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY`, so the iOS background refresh can read it on a locked phone; the pending login attempt uses `WHEN_UNLOCKED_THIS_DEVICE_ONLY`. |
| Read cache and drafts | `expo-sqlite`: `cached_care(member_id, resource, json, refreshed_at)` as in Room, plus `drafts`. The cache identity is member ID and role, as `CareModels.kt:11`: a role change wipes the table, N2 and drafts, and drops in-flight reads, as a member change does. No offline write queue; settings writes stay serialized in memory as in `WriteQueue.kt`. |
| Login | `expo-web-browser` `openAuthSessionAsync` + `expo-crypto`, hand-written PKCE. Initial URL, live URL and the browser result go through one single-use validator (ported `LoginAttempt`). |
| HTTP | All server requests go through N1. JS owns single-flight refresh, one replay per 401, invalid grant vs transient failure, `X-App-Version`, `Accept-Language` and the 426 stop, as in `ApiClient.kt`. |
| Server URL | `extra.serverUrl` in `app.config.ts`, default `https://fytorium.epaflix.com`. Release config fails unless HTTPS; debug may use `http://app:3000`. |
| Connectivity | `@react-native-community/netinfo` with native reachability and no probe URL (Android `VALIDATED`) decides Offline; request outcomes decide "Fytorium isn't responding", as in `Connectivity.kt:5-7`. |
| Background refresh | Android: N4's WorkManager worker also runs the unique 6 h periodic read with network and battery-not-low constraints, as `CareController.kt:88-94`. iOS: `expo-background-task` (BGTaskScheduler, best effort). |
| Push | `@react-native-firebase/app` + `messaging` for FCM tokens and rotation on both platforms, auto-registration off (`firebase.json`), iOS foreground presentation on. Android display through N4; iOS through APNs alerts (S2). See Push token lifecycle. |
| Photos | Android pick and capture through N6; iOS through `expo-image-picker`, whose copy is deleted after N10 prepares it. `expo.autolinking.android.exclude` lists `expo-image-picker` (its library manifest); its config plugin gets a camera usage text and `microphonePermission: false`; `android.blockedPermissions` lists `CAMERA` and `RECORD_AUDIO`. iOS CI asserts Info.plist has `NSCameraUsageDescription` and no `NSMicrophoneUsageDescription`. Library picks use quality 1 without editing, so the picker normally copies the file; if the copy fails it decodes, which iPhone memory handles, and the iPhone check picks a large PNG. iOS asks for camera permission before opening the camera; once denied, the Add photo sheet says the camera is off and offers Settings, and library picks still work. iOS camera captures arrive as a decoded image from the system camera in every iOS picker API; their size is bounded by the camera sensor, and the iPhone check covers capture to upload. `expo-image` with `cachePolicy: 'memory'`. |
| Other | `expo-clipboard`, RN `Share` and `Linking`, `expo-application`, `expo-device` (device name), `expo-localization` (24-hour setting, time zone). |
| Tests | Jest + `jest-expo` + `@testing-library/react-native` (labels, roles, selected state, 48 dp `minHeight`), a palette contrast test (4.5:1), native module unit tests, Maestro for e2e and screenshots. |

### Local native module

- **N1 HTTP client.** `request`, `upload` (multipart with progress events) and `download` (to a cache path). Android ports `ApiClient.kt:27-32`: OkHttp with `retryOnConnectionFailure(false)`, no redirects, no cookie jar, connect and read 10 s, call 20 s, a 1 s idle pool, and a separate zero-idle pool for `/api/app/token` with a one-shot body so no follow-up resends it; `evictAll` on network loss. iOS: one `URLSession` per pool with the same timeouts, a delegate that refuses every redirect, no cookie storage, and a separate session without keep-alive for the token endpoint. Both send each request at most once. No HTTP cache: OkHttp has none configured and both iOS sessions set `urlCache = nil`, so N2 is the only disk cache. `/original` returns base64, which the viewer shows as a data URI held in memory.
- **N2 Cache files.** Only `/thumb` and `/display` variants go to disk: 50 MiB LRU, 1 MiB per entry, one folder per member and role, wiped at session end and at member or role change. Originals (up to 15 MiB) stay in memory for the viewer.
- **N3 Restore signal.** The config plugin records `savedInstanceState != null` in `MainActivity` before Expo's `super.onCreate(null)`. Navigation and drafts restore only then, and never across logout or a member or role change. Swipe-away and iOS cold starts open Today at its root.
- **N4 Android push service.** Subclasses `ReactNativeFirebaseMessagingService`; the config plugin removes the library's own service so N4 is the only `MESSAGING_EVENT` handler, and the release APK check asserts that. Ports `PushMessages.kt`: drop unless the `push_ready` flag is set (see Push token lifecycle), channels `daily_reminder`, `weather_alert`, `completion_notice` (same IDs, so mutes survive), tag replacement, tap opens Today. It enqueues a network-constrained WorkManager one-shot that runs the JS cache refresh as a headless task, also when notification permission is denied. It calls `super` so `onTokenRefresh` still fires; JS also compares `getToken()` with the last registered token at start.
- **N5 First run**, once, before anything else: set `push_reset_pending`, copy the `asked` flag from prefs `notification-permission` into the module's prefs (so a denied Android 13+ prompt still opens system settings, as `NotificationScreen.kt:63-88`), then delete the Kotlin app's leftovers (none on a fresh install): prefs `credentials`, `diagnostics`, `notification-permission`; Keystore alias `fytorium.credentials`; `care-cache.db`; the thumbnail cache; `noBackupFilesDir/photo-drafts`; `cache/captures`; persisted URI grants; WorkManager jobs `care-cache-refresh` and `notification-cache-refresh`.
- **N6 Android photo input.** `PickVisualMedia` (single, or up to five for Identify) with persistable grants where offered, and `ACTION_IMAGE_CAPTURE` into a non-exported FileProvider limited to `cache/captures/`. A pending capture path and a pick result delivered after the process was killed are kept in prefs and exposed as `pendingResult()`, read on N3 restore. Startup sweeps captures older than ten minutes without a draft marker, as `PhotoImages.kt:20-41`. Session end releases persisted grants and deletes captures and drafts. No `CAMERA` or media permission.
- **N7** Clipboard copy with `EXTRA_IS_SENSITIVE` for invite links (Android 13+).
- **N8** Obtainium installed check (`<queries>` for both package names).
- **N9 iOS storage.** Exclude the SQLite, drafts and cache files from backup; wipe Keychain items on the first run after install (Keychain survives uninstall).
- **N10 `preparePhoto(uri)`.** Android `ImageDecoder` with a target size, as `PhotoImages.kt:45-58`; iOS `CGImageSourceCreateThumbnailAtIndex`. Longest edge 2560 px, orientation applied, JPEG 85, no metadata, never a full-size decode.

Debug-only code (synthetic Firebase config, cleartext for `app`, `oidc`, `fake-push`, a push replay hook for e2e) lives in a debug source set of the module and a debug-only JS entry; the release check proves it absent.

### Push token lifecycle

A session ends at logout or when refresh returns an invalid grant (`ApiClient.kt:102,114`); both run the same wipe. `push_ready` and `push_reset_pending` live in native storage owned by the module (SharedPreferences, UserDefaults), so N4 reads them without JS.

0. First run (N5) sets `push_reset_pending`, so the Kotlin install's token is deleted before the first registration. On a fresh install this costs one harmless `deleteToken`.
1. Session end, online or not, before the rest of the wipe. Logout first sends `POST /api/app/logout` best effort, as `ApiClient.kt:114-118`, and wipes whatever the result: clear `push_ready` (N4 drops everything while it is clear), set `push_reset_pending`, call `unregisterDeviceForRemoteMessages()`, which works offline and stops iOS alerts.
2. While `push_reset_pending` is set, the app calls FCM `deleteToken` whenever online and clears the flag only on success. FCM does not deliver to a deleted token, so nothing addressed to the previous session arrives afterwards.
3. Registration needs an active session and a clear reset flag. It runs one at a time per session: a run started while another is in flight waits, a token that changed during a post is posted again, and a result from an ended session is discarded. iOS first calls `registerDeviceForRemoteMessages()` and waits for APNs. Then the app gets the FCM token, posts it, and sets `push_ready` bound to that session. A new device never reuses a token bound to an old one (`subscriptions.ts:49-50`).
4. On every foreground entry and reconnect with an active session and a clear reset flag, the app runs step 3 again. Kotlin registers on login and on every start (`PushRegistration.kt:19`, `MainActivity.kt:58-61`); reconnect is added so a failed first post is retried without a new login; the server upsert keeps the device's enabled state.

Debug builds use a test token provider instead of Firebase, whose deletion can be made to fail and then succeed. For upgrade tests a debug-only launch argument seeds it with the synthetic token the Kotlin fixture registered.

On iOS, Turn on requests alert authorization when it is undetermined and opens Settings when denied, as `NotificationScreen.kt:82-88` does on Android 13+.

## Server changes

- **S1** (W2) `app-auth.ts`: accept `app=ios` with the same redirect URI. Extend `app-login` and `app-security` tests.
- **S2** (W8) `fcm.ts`: add an `apns` block (`alert` from title and body, `apns-collapse-id` from tag). Android keeps data-only handling. Extend `tests/api/fcm.test.ts` and `tests/unit/fcm.test.ts`.
- **S3** (W8) Device labels: "Android app" becomes "Mobile app" in `src/routes/settings/notifications/+page.svelte:105`; the app's fallback label is platform-neutral.
- **S4** (W9) Move the release, fingerprint, minimum-version, Firebase and Obtainium sections of `android/README.md` to `mobile/README.md` and update links: `README.md:320`, `README.md:346`, `+page.svelte:83`, `tests/browser/android-app.spec.ts`.

`MIN_APP_VERSION` is shared. It is raised only when every platform with users has an installable build.

## iOS

- Firebase iOS app, `GoogleService-Info.plist` from secret `GOOGLE_SERVICE_INFO_PLIST` (validated like `GOOGLE_SERVICES_JSON`, synthetic in debug), push entitlement, APNs key in Firebase. `scripts/firebase-setup` gains the iOS steps.
- No Mac in the homelab. Per wave, GitHub `macos-latest` runs Jest, N1/N9/N10 native tests, a simulator build and a Maestro smoke flow (launch, login screen, light and dark).
- `ios-release.yml` (W9) runs on `ios-v*` tags, off until the Apple secrets exist. `release-version` takes the platform prefix and gives `CFBundleShortVersionString` (versionName) and `CFBundleVersion` (versionCode). It archives, signs and uploads to TestFlight with `ITSAppUsesNonExemptEncryption: false`, so uploads skip the export-compliance prompt.
- First on the owner's iPhone, from a fresh install with notifications undetermined: login, Turn on notifications from the Notifications screen, take a photo and upload it, deny camera on a second install and see the Settings message, pick a large PNG, push delivery and tap, offline logout then reconnect, relaunch signed out and login as another member with no old alert and working new delivery.
- After that check passes, the owner adds household members as internal testers. The update stop opens the TestFlight app (`itms-beta://`); Obtainium is hidden. TestFlight builds expire after 90 days, so `mobile/README.md` tells the owner to push the next `ios-v*` patch tag before that, even without code changes.

## Test environment (D3)

Namespace `fytorium-e2e`, PodSecurity `privileged`, pod `fytorium-lab` on a worker with `/dev/kvm`: a `docker:dind` container (daemon on a pod-local unix socket only) and a privileged `work` container with git and the Docker CLI. Docker data and the checkout are two `subPath`s of one 25 GiB `truenas-iscsi` PVC (D8), a block device that cannot grow past its size. `scripts/mobile` prunes Docker images and Gradle caches when the volume passes 20 GiB; W1 records the peak of a clean build plus an emulator run. Both containers mount the checkout at the same path, `/src`, so bind mounts in `compose.yaml` (fake-push, WireMock mappings) and the emulator's `$PWD` mount resolve inside the daemon. Inside it the existing tooling runs: `./scripts/stack up` starts the synthetic stack in its own containers, and `./scripts/mobile e2e` starts the emulator container on the stack's network with the dnsmasq, guest DNS, Chrome and readiness gates from `android/docker/e2e`, moved to `mobile/e2e/`. Failure screenshots and window dumps go to `.scratch/e2e-failures/`. `scripts/mobile apk` and release builds run in the same daemon with a digest-pinned image, so any host with Docker can run them. The manifest is `mobile/e2e/lab.yaml`; `mobile/README.md` says how to create and delete it.

## Release checks

`mobile/release/` keeps `release-version`; its test resolves the script relative to itself. Two lanes:

- **Fixture lane** (PRs, `gate full`, upgrade tests): synthetic Firebase config with the explicit test-only flag, as `android/docker/release-check:5-13`, and a throwaway signer. It proves a missing config fails even with warm outputs, a non-HTTPS server URL fails, a synthetic config fails without the flag, and the production verifier rejects the throwaway signer. The APK it makes is never publishable.
- **Tag lane** (`android-release.yml`): production config and key; synthetic config rejected. A `workflow_dispatch` run with `publish: false` and a candidate version is the rehearsal: it builds and runs every check, creates no GitHub Release, and keeps the verified APK, `SHA256SUMS` and source SHA as a 7-day workflow artifact for the phone check.

Both lanes inspect the release APK: `aapt dump permissions` must match `mobile/release/permissions.txt`, taken from the last published Kotlin APK; additions fail review, and `CAMERA`, `RECORD_AUDIO`, `READ_MEDIA_*` and `READ_EXTERNAL_STORAGE` are blocked. The merged manifest keeps identity, `minSdk`, no backup, extraction rules and no cleartext, and has exactly one `MESSAGING_EVENT` service, N4's; DEX and Hermes bundle hold no debug hook class or test host; APK size is reported. The APK starts without Metro on the emulator and reaches the login screen without a crash. The tag lane also checks strictly higher versionCode than every published `android-v*`, package and version, certificate SHA-256 pin and throwaway-signer rejection, and publishes `fytorium-X.Y.Z.apk` and `SHA256SUMS` with a changelog over `mobile/`.

The first `android-v2.0.0` tag must point to the source SHA of the phone-tested rehearsal; any new commit needs a new rehearsal and phone check. Before that tag, on one Android phone: install the last Kotlin release, log in, enable push, then install the rehearsal APK signed with the production key over it. Check re-login, a real FCM notification and tap, and no old-device alert.

## Waves

Each wave is one diff. Before review: typecheck, lint, Jest, native module tests, fixture-lane release APK, iOS simulator build, and the wave's Maestro flows on the emulator with light and dark screenshots attached. Every council member scores it 9.5 or more.

| Wave | Scope | Acceptance |
| --- | --- | --- |
| W1 Scaffold | `mobile/` app shell, `app.config.ts`, module skeleton with N1, N3, N4, N5, N8, Jest, `scripts/mobile`, `mobile.yml`, lab manifest, fixture lane, `mobile` in `.dockerignore`. | Lab boots a fresh AVD; guest reaches `app`, `oidc`, `fake-push`; WireMock and fake-push see their mounted files; artifacts land in the checkout. Debug APK and iOS simulator build in CI. Release APK passes inspection. A fixture-lane APK installs over the seeded Kotlin install, and N5 leaves no legacy file, pref, key or job. Module test: registration waits for a successful `deleteToken`. Module test: N5 sets `push_reset_pending` and copies `asked`. N4 replay: dropped while `push_ready` is clear, shown once set, replaced by tag. Lab peak disk use recorded. Upgrade fixture (used here and in W2 and W8): the Kotlin app at the pinned commit `e0edd63` plus `mobile/e2e/kotlin-fixture/SeedTest.kt`, an instrumentation test the lab script copies into a `git worktree` of that commit before building. Each run makes a fresh synthetic token (as `NotificationScenario.kt:20`); the seed logs in, loads Today and a plant, registers the token through `PushDebugBridge`, asserts the server listed it, and stays logged in (a second case denies notifications). The Kotlin app, its test APK and the RN APKs are all signed with the fixture key; the RN debug provider gets the same token by launch argument. |
| W2 Session | Login, SecureStore, N1 in JS client, logout with local wipe and token lifecycle, push registration on login, update stop and 426, About, diagnostics, crash handler, S1. | Jest ports of `AuthTest` and `NetworkConfigTest`. Native transport tests on both platforms against a test server: redirect refused, cookie ignored, dropped token response not resent, 503 with `Retry-After: 0` not followed, token exchange on a fresh connection after the pool is primed, concurrent 401s refresh once. Maestro: login through the synthetic OIDC page, process death during browser login, online logout revokes the device so the old refresh token is rejected, logout offline then reconnect deletes the token through the debug provider after a failed first try, refresh with an invalid grant ends the session the same way, a first registration post returning 503 succeeds on the next foreground entry without a new login, a delayed old-token post during rotation does not overwrite the new token. Upgrade from the W1 fixture with debug APKs: after login nothing posts while `deleteToken` fails, the first post carries a token other than the seeded one, then it posts once deletion succeeds; then member A logs out offline and member B logs in with deletion pending, and A's replayed message is dropped. |
| W3 Shell and data | Tabs and per-tab stacks with the W6 back table, role gating, reselect, discard guards, N3 restore, SQLite cache with member binding and in-flight read guard, N2, connectivity, background refresh, theme. Stub screens for later waves. | RNTL test per W6 navigation row using stubs. Repository tests: cached read, logout and member-switch wipe, late response after wipe ignored, N2 cap eviction, background job cancelled at logout. Maestro: process kill vs swipe-away, owner downgraded to sitter with a hidden draft, a cached plant detail and a cached display photo (all gone afterwards), airplane mode vs a reachable server returning 503. |
| W4 Today | Today/Upcoming, task view sheet, receipts and Undo, row stability, Handle all, feed strip, rain watering, feed settings. | Jest ports of `TodayPresentationTest` occurrence cases. Maestro: `TodayScenario` up to the plant page, `FeedScenarios`, offline cold start shows cached Today. |
| W5 Plants and sites | Plants grid, plant page, add/edit, lifecycle, species search and change, sites. | Maestro: `PlantsE2eTest` up to species names, `SiteScenario`, the rest of `TodayScenario`. |
| W6 Species care | Species names, care overrides, unmatched species. | Maestro: `SpeciesCareScenario`, the rest of `PlantsE2eTest`. |
| W7 Photos | Add photo, Journal, viewer, Identify, N6, N10, draft cleanup. | Maestro: `PhotoScenario` with seeded MediaStore images and the emulator camera, including a capture and a pick returned after the process was killed. Device tests: the 8000×6000 BMP from `PhotoMemoryDeviceTest` prepares to 2560×1920 without a full decode; a rotated JPEG with EXIF and GPS comes out upright with no metadata; a 15 MiB original opens in the viewer on a 2 GiB AVD without a crash or disk copy; an old unmarked capture is swept at start; caches, captures and grants are gone after logout. |
| W8 More and push | Notifications and devices, members and invites with N7, custom tasks, Planta import, S2, S3. | Maestro: `NotificationScenario`, `HouseholdScenario`, `PlantaScenario`; fake-push send then N4 replay, cold and warm tap open Today, logged-out drop, headless refresh runs with notifications denied. Invite copy sets `EXTRA_IS_SENSITIVE`. Upgrade from a Kotlin debug build that denied notifications on Android 13+: Turn on opens system settings without a second prompt. Large-text landscape flow over the main screens. |
| W9 Release and delete | Android release workflow and tag lane, `ios-release.yml` (off until D7's secrets exist), S4, delete list. Every Maestro flow runs once more on the candidate. | The `publish: false` rehearsal passes every check; the Android phone upgrade check passes; the W2 and W8 upgrade tests rerun from the pinned Kotlin fixture after `android/` is deleted. `git ls-files android scripts/android .github/workflows/android.yml` is empty and `rg 'android/(README|docker|app|gradle)|scripts/android|roborazzi' -g '!docs/superpowers/**'` finds nothing. `./scripts/gate full` and browser tests pass. The lab recreates from `mobile/e2e/lab.yaml`. |

## Delete list (W9)

- `android/` entirely, after moving `docker/release-version` and its test, the e2e network scripts, the synthetic `google-services.json` and the launcher and notification icons to `mobile/`.
- `scripts/android`; `scripts/gate android` becomes `scripts/gate mobile`.
- `.github/workflows/android.yml` (replaced by `mobile.yml`); `android-release.yml` rewritten.
- The `android` line in `.dockerignore` (`mobile` is added in W1).
- Android rows in `docs/agent-workflow.md` and `README.md`.

Kept: `src/`, `tests/`, `migrations/`, `api/`, `compose.yaml`, `scripts/stack`, `scripts/firebase-setup` (extended), `CONTEXT.md` and historical specs.

## Risks

- **R1 Size.** About 9.3k lines of Kotlin and 1,030 tests carry the behaviour. W4, W5 and W7 will take several rounds.
- **R2 iOS.** No homelab Mac; real login, push and offline-logout checks on iOS are manual on the owner's iPhone, and release waits for the Apple account. Internal testing caps testers at 100 App Store Connect users.
- **R3 Visual regressions.** Screenshot review replaces pixel diffs (D4).
- **R4 APK size** grows by several MB with Hermes and React Native.
- **R5 Old device rows.** The old Android device stops receiving push after 90 idle days but stays listed until a member removes it.
