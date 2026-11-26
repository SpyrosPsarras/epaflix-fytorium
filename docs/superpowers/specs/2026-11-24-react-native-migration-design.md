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
| D7 | iOS ships through TestFlight, as T3 Code's iOS betas do. Needs the owner's Apple Developer Program membership; until then the iOS release job stays off. |

## Stack

`mobile/` has its own pnpm lockfile (`node-linker=hoisted`, required by Expo) and pins Node 22 LTS in `.nvmrc` and the build image. `mobile/android` and `mobile/ios` are generated and gitignored. Native behaviour Expo modules do not give lives in one local Expo module, `mobile/modules/fytorium-native` (Kotlin + Swift), listed in N1–N9.

| Concern | Choice |
| --- | --- |
| Framework | Expo SDK (latest stable at W1, pinned), New Architecture, TypeScript strict. |
| Navigation | `@react-navigation/bottom-tabs` + `native-stack`; `predictiveBackGestureEnabled`. No URL deep links, so no expo-router. |
| UI | `react-native-paper` (MD3), `@expo/vector-icons`, `@react-native-community/datetimepicker`. Palette from `Theme.kt`, system light/dark. |
| Credentials | `expo-secure-store`, `WHEN_UNLOCKED_THIS_DEVICE_ONLY`. Tokens and the pending login attempt. |
| Read cache and drafts | `expo-sqlite`: `cached_care(member_id, resource, json, refreshed_at)` as in Room, plus `drafts`. No offline write queue; settings writes stay serialized in memory as in `WriteQueue.kt`. |
| Login | `expo-web-browser` `openAuthSessionAsync` + `expo-crypto`, hand-written PKCE and JSON code exchange (the server takes JSON on `/api/app/token`). |
| HTTP | `fetch`, XHR for upload progress. One client: single-flight refresh, one replay per 401, invalid grant vs transient failure as in `ApiClient.kt`, `X-App-Version`, `Accept-Language`, 426 stop. |
| Server URL | `extra.serverUrl` in `app.config.ts`, default `https://fytorium.epaflix.com`; release config fails unless HTTPS. Debug may use `http://app:3000`. |
| Connectivity | `@react-native-community/netinfo`, `reachabilityUrl` set to `<serverUrl>/healthz`, plus request outcomes. |
| Background refresh | `expo-background-task` for the 6 h read (WorkManager / BGTaskScheduler, iOS best effort). The push-triggered one-shot runs inside the push handler (N4). |
| Push | `@react-native-firebase/app` + `messaging` for FCM tokens and rotation on both platforms. Android display through N4; iOS through APNs alerts (S2). |
| Photos | `expo-image-picker` for the system photo picker; camera through N6 if the picker's camera needs `CAMERA` (decided by the W1 permission check). `expo-image-manipulator` (2560 px, JPEG 85, no EXIF), `expo-file-system`. Authenticated images use our own file cache (50 MiB LRU, 1 MiB per image, wiped per member); `expo-image` runs with `cachePolicy: 'memory'`. |
| Other | `expo-clipboard`, RN `Share` and `Linking`, `expo-application`, `expo-device` (device name), `expo-localization` (24-hour setting, time zone). |
| Tests | Jest + `jest-expo` + `@testing-library/react-native` (labels, roles, selected state, 48 dp `minHeight`), a palette contrast test (4.5:1), Maestro for e2e and screenshots. |

### Local native module

- **N1** Android `OkHttpClientFactory`: `retryOnConnectionFailure(false)`, no redirects, no cookie jar, 1 s idle keep-alive (the server closes at 5 s), `evictAll` on network loss. Requests to `/api/app/token` send `Connection: close`. The one-time login code exchange must never be sent twice; refresh has the server's 120 s grace (`app-auth.ts:139-150`).
- **N2** iOS: an ephemeral `URLSession` configuration for the same rules, proven by the W2 transport test.
- **N3** Restore signal: Android reports whether the activity was recreated with saved state. Navigation and drafts restore only then, and never across logout or a member or role change. Swipe-away and iOS cold starts open Today at its root.
- **N4** Android `FirebaseMessagingService` replacing the library's service in the manifest. Ports `PushMessages.kt`: drop without a session flag (written by JS at login, cleared at logout), channels `daily_reminder`, `weather_alert`, `completion_notice` (same IDs, so mutes survive), tag replacement, tap opens Today, enqueue the one-shot read refresh even when notification permission is denied.
- **N5** Legacy cleanup on first run, once: delete prefs `credentials`, `diagnostics`, `notification-permission`, Keystore alias `fytorium.credentials`, `care-cache.db`, thumbnail cache, `noBackupFilesDir/photo-drafts`, `cache/captures`, released URI grants, and WorkManager jobs `care-cache-refresh` and `notification-cache-refresh`. Then FCM `deleteToken`; the flag is set only after it succeeds, and registration waits for it.
- **N6** Camera capture through `ACTION_IMAGE_CAPTURE` and a non-exported FileProvider limited to `cache/captures/`, if needed.
- **N7** Clipboard copy with `EXTRA_IS_SENSITIVE` for invite links (Android 13+).
- **N8** Obtainium installed check (`<queries>` for both package names).
- **N9** iOS: exclude the SQLite and drafts files from backup; wipe Keychain items on the first run after install (Keychain survives uninstall).

Debug-only code (synthetic Firebase config, cleartext for `app`, `oidc`, `fake-push`, a push replay hook for e2e) lives in a debug source set of the module and a debug-only JS entry; the release check proves it absent.

## Server changes

- **S1** (W2) `app-auth.ts`: accept `app=ios` with the same redirect URI. Extend `app-login` and `app-security` tests.
- **S2** (W8) `fcm.ts`: add an `apns` block (`alert` from title and body, `apns-collapse-id` from tag). Android keeps data-only handling. Extend `tests/api/fcm.test.ts` and `tests/unit/fcm.test.ts`.
- **S3** (W8) Device labels: "Android app" becomes "Mobile app" in `src/routes/settings/notifications/+page.svelte:105`; the app's fallback label is platform-neutral.
- **S4** (W9) Move the release, fingerprint, minimum-version, Firebase and Obtainium sections of `android/README.md` to `mobile/README.md` and update links: `README.md:320`, `README.md:346`, `+page.svelte:83`, `tests/browser/android-app.spec.ts`.

`MIN_APP_VERSION` is shared by both platforms. It is raised only when both have an installable build.

## iOS

- Firebase iOS app, `GoogleService-Info.plist` from secret `GOOGLE_SERVICE_INFO_PLIST` (validated like `GOOGLE_SERVICES_JSON`, synthetic in debug), push entitlement, APNs key in Firebase. `scripts/firebase-setup` gains the iOS steps.
- Offline logout: APNs shows alerts without running app code, so logout always calls `unregisterDeviceForRemoteMessages()`, which works without a network, before wiping local data.
- No Mac in the homelab. Per wave, GitHub `macos-latest` runs Jest, a simulator build and a Maestro smoke flow (launch, login screen, light and dark). Real login, push and upgrade on iOS are checked on the owner's iPhone with a TestFlight build before the first iOS release. iOS parity rests on the shared JS and these checks.
- The update stop links to the TestFlight invite; Obtainium is hidden.

## Test environment (D3)

Namespace `fytorium-e2e`, PodSecurity `privileged`, one pod `fytorium-lab` pinned to a worker with `/dev/kvm`: a `docker:dind` container (12 GiB memory, `/var/lib/docker` on a 25 GiB local-path PVC) and a `work` container with git and the Docker CLI. Inside it the existing tooling runs unchanged: `./scripts/stack up` starts the synthetic stack (separate containers, so OIDC and fake-push keep port 8080), and `./scripts/mobile e2e` starts the emulator container on the stack's network with the current dnsmasq, guest DNS, Chrome and readiness gates from `android/docker/e2e`, moved to `mobile/e2e/`. Failure screenshots and window dumps go to `.scratch/e2e-failures/`. `scripts/mobile apk` and release builds run in the same dind with a digest-pinned image, so any host with Docker can run them too. The manifest is `mobile/e2e/lab.yaml`; `mobile/README.md` says how to create and delete it.

## Release checks

`mobile/release/` keeps `release-version` and its test unchanged. The no-CI gate and both workflows run:

- **Before signing:** full JS suite, release APK built from a clean prebuild, rejecting a missing or synthetic `GOOGLE_SERVICES_JSON` and a non-HTTPS server URL even with warm outputs.
- **APK inspection:** `aapt dump permissions` equals the allow-list `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE` (anything Expo adds goes in `blockedPermissions`); merged manifest has no cleartext and no debug receiver; DEX and Hermes bundle have no debug hook class or test host; APK size reported.
- **Launch:** the signed dry-run APK starts without Metro on the emulator and reaches the login screen without a crash.
- **Tag release:** strictly higher versionCode than every published `android-v*`, package and version check, certificate SHA-256 pin, throwaway signer rejected, `fytorium-X.Y.Z.apk`, `SHA256SUMS`, changelog over `mobile/`.

## Waves

Each wave is one diff. Before review: typecheck, lint, Jest, debug APK, iOS simulator build, the wave's Maestro flows on the emulator with light and dark screenshots attached. Every council member scores it 9.5 or more.

| Wave | Scope | Acceptance |
| --- | --- | --- |
| W1 Scaffold | `mobile/` app shell, `app.config.ts`, local module skeleton with N1, N4, N5, N8, Jest, `scripts/mobile`, `mobile.yml`, lab namespace. | Lab boots a fresh AVD, guest reaches `app`, `oidc`, `fake-push`. Debug APK and iOS simulator build in CI. Permission allow-list holds. A release APK signed with a throwaway key installs over a populated Kotlin install signed with the same key; N5 leaves no legacy file, pref, key or job; registration happens only after `deleteToken`. N4 receives a replayed data message, drops it without a session and replaces by tag. |
| W2 Session | Login (initial and live callback URLs through one single-use validator), SecureStore, API client, N2, logout with local wipe, update stop and 426, About, diagnostics, crash handler, S1. | Jest ports of `AuthTest` and `NetworkConfigTest`. Native transport test on both platforms: dropped token response, redirect, cookie, concurrent 401s, no second code exchange. Maestro: login through the synthetic OIDC page, process death during browser login, logout offline. |
| W3 Shell and data | Tabs and per-tab stacks with the W6 back table, role gating, reselect, discard guards, N3 restore, SQLite cache with member binding and in-flight read guard, thumbnail cache, connectivity, background refresh, theme. Stub screens for later waves. | RNTL test per W6 navigation row using stubs. Repository tests: cached read, logout and member-switch wipe, late response after wipe ignored, thumbnail cap eviction, background job cancelled at logout. Maestro: process kill vs swipe-away, role downgrade with a hidden draft. |
| W4 Today | Today/Upcoming, task view sheet, receipts and Undo, row stability, Handle all, feed strip, rain watering, feed settings. | Jest ports of `TodayPresentationTest` occurrence cases. Maestro: `TodayScenario` up to the plant page, `FeedScenarios`, offline cold start shows cached Today. |
| W5 Plants and sites | Plants grid, plant page, add/edit, lifecycle, species search and change, sites. | Maestro: `PlantsE2eTest` up to species names, `SiteScenario`, the rest of `TodayScenario`. |
| W6 Species care | Species names, care overrides, unmatched species. | Maestro: `SpeciesCareScenario`, the rest of `PlantsE2eTest`. |
| W7 Photos | Add photo, Journal, viewer, Identify, N6, draft cleanup. | Maestro: `PhotoScenario` with seeded MediaStore images and the emulator camera. Device test with the 144 MB / 48 MP rotated fixture from `PhotoMemoryDeviceTest`: no full decode, bounded output, no EXIF, no original on disk; caches wiped at logout. |
| W8 More and push | Notifications and devices, members and invites with N7, custom tasks, Planta import, S2, S3. | Maestro: `NotificationScenario`, `HouseholdScenario`, `PlantaScenario`; fake-push send then N4 replay, cold and warm tap open Today, logged-out drop. Invite copy sets `EXTRA_IS_SENSITIVE`. Large-text landscape flow over the main screens. |
| W9 Release and delete | Release workflow and checks, S4, delete list. Every Maestro flow runs once more on the candidate. | Dry-run release passes every release check. `android/` and `scripts/android` do not exist; `rg 'android/|scripts/android|roborazzi'` finds nothing outside `docs/superpowers/`. `./scripts/gate full` and browser tests pass. Lab recipe recreates the namespace from `mobile/e2e/lab.yaml`. |

## Delete list (W9)

- `android/` entirely, after moving `docker/release-version` and its test, the e2e network scripts, the synthetic `google-services.json` and the launcher and notification icons to `mobile/`.
- `scripts/android`; `scripts/gate android` becomes `scripts/gate mobile`.
- `.github/workflows/android.yml` (replaced by `mobile.yml`); `android-release.yml` rewritten.
- The `android` line in `.dockerignore` becomes `mobile`.
- Android rows in `docs/agent-workflow.md` and `README.md`.

Kept: `src/`, `tests/`, `migrations/`, `api/`, `compose.yaml`, `scripts/stack`, `scripts/firebase-setup` (extended), `CONTEXT.md` and historical specs.

## Risks

- **R1 Size.** About 9.3k lines of Kotlin and 1,030 tests carry the behaviour. W4, W5 and W7 will take several rounds.
- **R2 iOS.** No homelab Mac; real-login, push and upgrade checks on iOS are manual on the owner's iPhone, and release waits for the Apple account.
- **R3 Visual regressions.** Screenshot review replaces pixel diffs (D4).
- **R4 APK size** grows by several MB with Hermes and React Native.
- **R5 Old device rows.** The old Android device stops receiving push after 90 idle days but stays listed until a member removes it.
