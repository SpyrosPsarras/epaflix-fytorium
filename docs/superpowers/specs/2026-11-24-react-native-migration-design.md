# React Native migration

Replace the Kotlin/Compose app in `android/` with one TypeScript React Native app in `mobile/` that ships to Android and iOS. Parity with the current Android app is the scope; no new features. The SvelteKit server and web UI stay.

Behaviour references: `CONTEXT.md`, `android/README.md` and the W1–W6 specs in this folder, final amendments winning. They describe behaviour, not code; Kotlin paths in them stay as history.

## Owner decisions (2026-11-24)

| Code | Decision |
| --- | --- |
| D1 | Android and iOS. |
| D2 | Existing Android installs upgrade in place and log in once more. The first run deletes the old FCM token so registration does not hit 409. |
| D3 | Emulator e2e runs in a disposable privileged namespace on the homelab cluster, deleted after the migration. |
| D4 | Maestro screenshots of key screens in light and dark go into each wave's PR for review. No pixel-diff gate. The 276 Roborazzi baselines are deleted with the Kotlin app. |
| D5 | Expo with Continuous Native Generation (CNG) and a dev client. No Expo Go, EAS or Expo Push Service. Builds run on our own runners. |
| D6 | The user asked the council to loop until every item scores 9.5. That replaces the three-round cap in `docs/agent-workflow.md` for this migration. Scope stays parity. |

## Stack

| Concern | Choice | Why |
| --- | --- | --- |
| Framework | Expo SDK (latest stable when W1 starts, pinned), React Native New Architecture, TypeScript strict | First-party modules cover nearly every native need and upgrade together. `mobile/android` and `mobile/ios` are generated and gitignored. |
| Navigation | `@react-navigation/bottom-tabs` + `native-stack` | W6 is per-tab stacks, reselect, discard guards. Maps one to one. No URL deep links, so no expo-router. |
| UI | `react-native-paper` (MD3), `@expo/vector-icons`, `@react-native-community/datetimepicker` | Dialogs, snackbar, segmented buttons, menus. Fixed green palette from `Theme.kt`, light and dark from the system. |
| Tokens and pending login | `expo-secure-store` | Android Keystore and iOS Keychain. |
| Offline read cache | `expo-sqlite`, one table `cached_care(member_id, resource, json, refreshed_at)` | Same model as Room's `CachedCare`. No offline write queue exists today and none is added. |
| Login | `expo-web-browser` `openAuthSessionAsync` + `expo-crypto`, hand-written PKCE and JSON code exchange | The server takes JSON on `/api/app/token`; form-encoded OAuth libraries would need a server change. |
| HTTP | `fetch`, XHR for upload progress, one client with single-flight refresh, one replay per 401, `X-App-Version`, `Accept-Language`, 426 handling | Same contract as `ApiClient.kt`. |
| Transport retries | Android: a local config plugin installs an `OkHttpClientFactory` with `retryOnConnectionFailure(false)`, no redirects, no cookies. iOS: `NSURLSession` does not replay POSTs. | A replayed refresh token logs the user out (`app-refresh-retry` contract). |
| Connectivity | `@react-native-community/netinfo` (`isInternetReachable`) plus request outcomes | Separates offline from "Fytorium isn't responding". |
| Background refresh | `expo-background-task` | Periodic 6 h read on Android WorkManager and iOS BGTaskScheduler. iOS timing is best effort. |
| Push | Spike in W1 decides: `expo-notifications` (native device token) if it keeps the logged-in guard, channels and tag replacement for data-only messages; otherwise `@react-native-firebase/messaging` + a small local Android receiver module. | `delivery.ts` sends `{title, body, tag, url}` as FCM data; `expo-notifications` may auto-present data with `title`. |
| Photos | `expo-image-picker` (system picker, camera, `getPendingResultAsync` for process death), `expo-image-manipulator` (2560 px, JPEG 85, EXIF dropped), `expo-image`, `expo-file-system` | No broad storage or camera-roll permission on Android; iOS needs camera and photo usage strings. |
| Clipboard, share, links | `expo-clipboard`, RN `Share`, `Linking`, `expo-application` | Invite links, diagnostics, update stop. |
| Tests | Jest + `jest-expo` + `@testing-library/react-native`; Maestro for e2e and screenshots | Replaces Robolectric, Roborazzi and UiAutomator. |

## Server changes (small, in W2 and W8)

- **S1.** `app-auth.ts`: accept `app=ios` beside `app=android`, same redirect URI `com.epaflix.fytorium:/oauth2redirect`. Extend `app-login` and `app-security` tests.
- **S2.** `fcm.ts`: add an `apns` block (`alert` from title and body, `apns-collapse-id` from tag) to each message. Android ignores it and keeps data-only handling; iOS shows the alert. Extend `fcm.test.ts`.
- **S3.** When `android/README.md#obtainium` moves to `mobile/README.md#obtainium`, update `src/routes/settings/notifications/+page.svelte:83` and `tests/browser/android-app.spec.ts`.

No other API changes. All other app suites stay as they are.

## Upgrade path for existing Android installs

Same `applicationId` `com.epaflix.fytorium`, same PKCS12 signing key, `minSdk` 29, versionCode above every published release (`android/docker/release-version` rule, moved to `mobile/release/`). First tag `android-v2.0.0`.

On first launch the app finds no SecureStore credentials and shows login. Before registering for push it calls the FCM `deleteToken` path once (flag in SQLite), so the new device gets a fresh token. It deletes the leftover `care-cache.db`, thumbnails and the WorkManager job `care-cache-refresh` by name through the same local Android module that sets the OkHttp factory. The old app device stays listed until it idles out after 90 days or the owner removes it.

## iOS specifics

- Build and simulator tests need macOS. The homelab has none, so iOS builds and Maestro iOS flows run on GitHub Actions macOS runners.
- Distribution needs an Apple Developer Program membership, an APNs key uploaded to the Firebase project and a provisioning profile. These are owner steps. Until they exist, CI builds an unsigned simulator app and the iOS release job stays off.
- Push: APNs through FCM with S2. Background refresh is best effort.
- Update stop: links to the release page; Obtainium is Android-only and hidden on iOS.

## Tooling

- **`scripts/mobile`** replaces `scripts/android`: `install`, `typecheck`, `lint`, `test` (Jest), `bundle` (Hermes export + release-bundle grep for debug-only code and test hosts), `apk`, `release-*`, `e2e`. JS commands run with Node on the host; native commands run in a builder pod or CI.
- **Builder:** `reactnativecommunity/react-native-android` pod in `t3-build-homelab` (2 CPU, 4 GiB). APK builds and `expo prebuild` run there.
- **Emulator (D3):** disposable namespace `fytorium-e2e` (PodSecurity privileged), one pod with `/dev/kvm`, the Android emulator, Maestro, and the synthetic stack. Deleted at the end of W9.
- **CI:** `android.yml` becomes `mobile.yml`: JS job (typecheck, lint, Jest, bundle check), Android job (prebuild, debug APK, release APK size), iOS job on `macos-latest` (prebuild, simulator build). `android-release.yml` keeps the tag rule, monotonic versionCode, `GOOGLE_SERVICES_JSON` validation, signing, certificate SHA-256 check, `fytorium-X.Y.Z.apk` and `SHA256SUMS`.
- **Gate:** `scripts/gate mobile` replaces `gate android`; `gate full` runs the mobile JS checks and bundle check instead of Gradle.

## Waves

Each wave is one reviewable diff. Before review: Jest, typecheck and lint pass, the debug APK builds, and the wave's Maestro flows pass on the emulator with screenshots attached. Council passes each wave at 9.5 from every member.

| Wave | Scope | Acceptance |
| --- | --- | --- |
| W1 Scaffold and spikes | `mobile/` Expo app, `app.config.ts` (id, scheme, minSdk 29, no backup, debug-only cleartext hosts `app`, `oidc`, `fake-push`, Obtainium `<queries>`), local Android module (OkHttp factory, legacy cleanup, Obtainium check), Jest, `scripts/mobile`, CI skeleton, emulator namespace. Spikes: data-only push guard and tag replacement, release signing, OkHttp no-retry. | Debug APK and iOS simulator build in CI. A release APK signed with a throwaway key installs over the Kotlin release signed with the same key. Push spike outcome recorded in this spec. |
| W2 Session | PKCE login, SecureStore, API client, logout with local wipe, update stop and 426, About, diagnostics, crash handler, S1. | Ported `AuthTest`/`NetworkConfigTest` cases in Jest; Maestro login/logout through the real synthetic OIDC page. |
| W3 Shell and data | Tabs and per-tab stacks with W6 back table, role gating, reselect, discard guards, state restore after process death, SQLite cache with member binding, thumbnails (50 MiB LRU, 1 MiB per image), connectivity, background refresh, theme. | Every W6 navigation row as an RNTL test; offline cold start shows cached Today. |
| W4 Today | Today/Upcoming, task view sheet, receipts and Undo, row stability rules, Handle all, feed strip, rain watering, feed settings. | `TodayScenario` and `FeedScenarios` as Maestro flows; occurrence-identity cases from `TodayPresentationTest` in Jest. |
| W5 Plants and sites | Plants grid, plant page, add/edit, lifecycle, species search and change, sites. | `PlantsE2eTest`, `SiteScenario` flows. |
| W6 Species care | Species names, care overrides, unmatched species. | `SpeciesCareScenario` flow. |
| W7 Photos | Add photo, Journal, viewer, Identify, draft cleanup. | `PhotoScenario`; uploaded JPEG has no EXIF; camera capture survives process death. |
| W8 More and push | Notifications and devices, members and invites, custom tasks, Planta import, push receiver, S2. | `NotificationScenario`, `HouseholdScenario`, `PlantaScenario` flows; fake-push delivery and tap opens Today. |
| W9 Release and delete | New release workflow, upgrade test, S3, delete the Kotlin app and everything only it used. | Dry-run release passes the certificate check; Kotlin install upgrades in place; `rg -i 'gradle|kotlin|roborazzi|compose'` finds nothing outside historical specs; gate passes. |

## Delete list (W9)

- `android/` entirely: `app/` (Kotlin sources, tests, `androidTest`, screenshots, Room schema, `build.gradle.kts`, `proguard-rules.pro`), Gradle wrapper and settings, `docker/` (`Dockerfile`, `fetch-sdk`, `emulator-package.xml`, `e2e`, `release`, `release-check`). `release-version` and its test, the synthetic `google-services.json` and the launcher and notification icons move to `mobile/` first.
- `scripts/android`.
- `.github/workflows/android.yml` (replaced by `mobile.yml`); `android-release.yml` rewritten in place.
- The `android` line in `.dockerignore` becomes `mobile`.
- Android rows in `docs/agent-workflow.md` and `README.md` rewritten for `mobile/`.

Kept: all of `src/`, `tests/`, `migrations/`, `api/`, `scripts/firebase-setup`, the fake-push stack service, `CONTEXT.md` and the historical specs.

## Risks

- **R1 Size.** About 9.3k lines of Kotlin and 1,000 tests carry the behaviour. W4, W5 and W7 will take several council rounds.
- **R2 Process death.** Stacks, scroll and drafts must be persisted explicitly (navigation state and a draft table in SQLite). Invite links, Planta codes and originals are never persisted.
- **R3 Push.** Data-only delivery when the app is killed is unproven for both candidate libraries. W1 spike decides before W8 depends on it.
- **R4 iOS.** No Mac in the homelab; iOS checks are CI-only and release is blocked on the owner's Apple account.
- **R5 Visual regressions.** Screenshot review replaces pixel diffs (D4).
- **R6 APK size** grows by several MB with Hermes and React Native.
