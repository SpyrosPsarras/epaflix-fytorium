# Fytorium Android

Kotlin and Compose phone app, API 29 through 35. Login, Today and Upcoming use the real server. Today, Upcoming and previously loaded plant data stay readable offline. Plants has a read-only cached view; editing and the Journal remain for later tickets. No analytics, external crash service or notification SDK is included.

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

The emulator container joins this worktree's Compose network. Inside the container, dnsmasq forwards guest DNS to Docker's resolver at 127.0.0.11. The guest emulator is started with that container's IP as its DNS server. It can therefore reach both canonical origins, `http://app:3000` and `http://oidc:8080`, without published ports, changed OIDC callbacks or host DNS edits. Before instrumentation, bounded readiness gates query dnsmasq directly for both names, check stack HTTP from the container, then issue HTTP requests to both names with `adb shell` inside the guest. Each gate has a configurable deadline and reports the failed probe and DNS logs. dnsmasq does not cache early negative answers. The DNS forwarder is unchanged; only `-feature -WiFiPacketStream` was added to the emulator flags. This disables Netsim Wi-Fi packet streaming, which can strand guest DNS under CPU load, and uses the QEMU DNS backend with the same container-local dnsmasq server. An instrumented test still asserts both endpoints respond from the guest, not merely from its container. adb binds to container-local `127.0.0.1:5037`, with inherited adb endpoints cleared and the fresh `emulator-5554` selected. The container uses bridge networking, not the host network, and mounts no host adb socket.

`./scripts/android e2e` requires a running stack from `./scripts/stack up`. It boots a fresh AVD with `--device /dev/kvm`, installs the debug APK and runs instrumented tests. The container runs as the caller's UID with the KVM device group, so caches remain caller-owned. The AVD is ephemeral, so the second run has no old browser cookies or device credentials. The script checks that Chrome is installed, selects it as the guest debug app so it reads test command-line flags, and disables first-run onboarding and the default-browser prompt. These settings exist only in the disposable AVD; they do not bypass OIDC or change the app's network policy. Boot waits are bounded. The emulator container has a unique name and is removed on exit, including failure. INT and TERM exit through cleanup. The stack is never started, rebuilt or stopped by e2e; it stays running for the next run.

Readiness timeout overrides are whole seconds from 1 to 3600:

| Environment variable | Default | Readiness check |
| --- | --- | --- |
| `E2E_BOOT_TIMEOUT` | 360 | Guest reports `sys.boot_completed=1` |
| `E2E_PM_TIMEOUT` | 180 | Guest package manager responds to `pm path android` |
| `E2E_DNS_TIMEOUT` | 180 | Container dnsmasq resolves `app` and `oidc`, and stack HTTP responds |
| `E2E_GUEST_NETWORK_TIMEOUT` | 180 | Guest reaches both HTTP endpoints before instrumentation; also bounds validated-network, `app` DNS and HTTP recovery after airplane mode |

To run five times on exactly the same stack:

```
./scripts/container install --frozen-lockfile
./scripts/stack up
for run in 1 2 3 4 5; do
  E2E_LOGIN=1 ./scripts/android e2e || exit 1
done
./scripts/stack down
```

The launch, guest networking and real Android Keystore round-trip tests always run. `E2E_LOGIN=1` also enables the Custom Tab scenario against the merged A01 login endpoints. It waits for the local provider in Chrome, enters and verifies subject `synthetic-a03-owner`, scrolls forward to Sign-in and waits for the redirect to return to Fytorium. It seeds a synthetic site and Testoria plant through the bearer API, advances the stack's `__test/clock`, completes a late watering task and undoes it, checks a future Upcoming row has no Snooze, then enables real guest airplane mode. Cached Today stays visible, writes are disabled and a disabled click produces no care event after reconnecting. After disabling airplane mode, a readiness gate bounded by `E2E_GUEST_NETWORK_TIMEOUT`, default 180 seconds, waits for the default network to be validated, `app` to resolve, and a fresh HTTP health request to succeed before the next scenario request. Only readiness probes retry, not repository refreshes or scenario API calls. Network loss evicts idle API sockets so requests cannot reuse connections aborted by airplane mode; transport retries remain disabled. The scenario deletes its plant/site, resets the clock, logs out, checks the local wipe and checks WorkManager cancellation. Interrupted-run recovery deletes only fixtures with this scenario's exact synthetic names. The phone viewport needs scrolling past the provider's claims textarea. Only forward swipes are used, since a scroll-to-beginning gesture can trigger Chrome's pull-to-refresh and erase the entered subject. No mocked token endpoint replaces the server. The Google APIs image supplies Chrome for Custom Tabs. The emulator stays on homePC, not GitHub CI.

## Login and diagnostics

Login opens `/auth/login` with `app=android`, the fixed `com.epaflix.fytorium:/oauth2redirect` URI, S256 challenge and a fresh 256-bit state. The app checks the exact hostless URI, state, duplicate query parameters and a ten-minute local login-attempt expiry. A validated redirect consumes the attempt before exchanging a code. Invalid or stale redirects cannot consume a newer pending attempt. Redirects received after login are ignored with a predefined debug-only logcat event, not a user error. The server's one-time code expiry remains 60 seconds.

Both tokens and the pending verifier/state are AES-256-GCM encrypted with an Android Keystore key. Pending state survives process recreation. No backup or device transfer may carry credential files. All API requests use a bearer token when logged in. The Application owns the API client, credential store and SessionController. SessionController exposes loggedIn, busy, message and updateRequired as a StateFlow, deriving loggedIn only from the credential store token flow. Login completion, version checks, refresh and logout run in its process-scoped coroutine scope. Its redirect queue and in-flight exchanges survive Activity recreation; Activities only forward fresh intents and render session state. Code and refresh exchanges use a separate HTTP client with no idle pooled connections. The browser round-trip can outlive the startup API socket, and these single-use requests must not automatically replay after a broken connection. Ordinary API calls still use their connection pool. A process-wide lock serializes refreshes across clients, re-reads the store, and reuses tokens already rotated by another caller. A 401 triggers at most one refresh and one replay. Only an explicit `invalid_grant` or `invalid_token` refresh rejection clears credentials and returns to login. Rate limits, server failures, malformed responses and network errors retain tokens and show a retry message. A retry sends the same stored refresh token after a lost reply, matching login contract Amendment 1. Restored Activities skip the old redirect intent; fresh redirects still run through `onNewIntent`. Logout calls the server and clears local credentials even if the network fails. Session loss also clears the member-scoped Room and thumbnail caches and cancels background refresh.

At startup, and again after login, `/api/meta` gates on 426 or a newer semantic `minAppVersion`. A missing endpoint, 404, remains compatible with older servers. The real meta response also carries serverVersion, today, timezone and role; a null minAppVersion imposes no minimum. The request sends `X-App-Version`. Network failures show a retry message instead of an update requirement.

More → About shows the build's version and copies at most 64 predefined error events, bounded to 8 KiB. Exceptions, intent URLs, response bodies and user input cannot enter that log. The crash handler records only APP_CRASH and terminates without logging throwable text. No tokens, authorization codes or verifiers enter diagnostics.

## Theme and tests

This base has no `src/app.css`. The palette uses the actual web layout colours and manifest: background #fff7ed, text #292524, accessible interactive orange #9a3412, brand orange #ea580c. Dark mode uses warm stone surfaces and lighter orange. The system setting chooses the palette; dynamic device colours are intentionally disabled. White text on #ea580c fails WCAG AA, so text buttons use the web's darker orange.

MockWebServer tests cover RFC 7636 PKCE, state/URI rejection, contract exchange, single-use state, fresh connections for code and refresh exchanges, refresh rotation, concurrent requests, explicit refresh rejection, retryable failures, second 401, logout and version checks. Source-policy assertions and device tests restrict debug cleartext; Activity recreation tests check process-owned client/store identity, queued redirects during a blocked version check, and token responses arriving after recreation. Duplicate and stale-then-valid redirects are covered, as is the process-restore replay guard. Robolectric tests exercise every shell screen with Compose accessibility checks and explicit 48 dp target/4.5:1 contrast assertions.

Twenty-eight checked-in Roborazzi reference PNGs cover the shell screens, populated Today, Upcoming, offline Today, sitter Today/Upcoming and cached plant list/detail at 360×800 in both themes. Task screen tests check selected tab semantics, labelled settings and the single-target Show completed toggle, handle-all visibility, server-controlled Undo, offline/server-error banners, disabled controls, provenance, pot-form validation and accessibility. `screenshots` verifies them. Deliberate UI changes update references with `ANDROID_RECORD_SCREENSHOTS=1 ./scripts/android screenshots`. Review the changed PNGs before staging. All generated test outputs stay in ignored build directories.

## Today, Upcoming and offline reads

`Repository` uses the one process-owned `ApiClient` for every API request, including thumbnails. JSON response models read the OpenAPI field names directly, without reflective serialization. Room stores member-id/resource-keyed JSON snapshots for Today, Upcoming, the plant list and plant details with history and schedules. Snapshots preserve the server's sections, ordering and provenance. Details load when opened, not for every plant during background refresh. Logout clears both caches even if revocation fails. A member or role change wipes the old rows, thumbnails and visible state before fetching the new member's data. A generation check prevents in-flight reads from restoring data after logout. Cache files are excluded from cloud backup and device transfer. Room exports its schema to `app/schemas/` and discards incompatible cache schemas with destructive migration.

Today uses a Material 3 Fytorium app bar, Today/Upcoming tabs and pull-to-refresh. The app-bar settings icon opens a bottom sheet with labelled Sort and Filter controls and a single toggleable Show completed row. Today offers Done, Snooze 2 days, Skip, Undo, per-plant Handle all tasks and the separate repot-with-pot-details form. Based on shows input, value, adjustment, source, rule id, factor, fetched time and reason where supplied. Upcoming uses the same server sections and saved task-type/plant/site sort, all/indoor/outdoor filter and Show completed preference. These preferences are saved through `/api/tasks/settings`; no settings write is cached or queued. The view metadata inside a task snapshot describes that saved view.

Undo permission comes from the server. The remaining action visibility follows these web rules:

- `Today.svelte`: Done and Skip for applicable rows; Snooze only when `status == due` and `dueOn <= list.today`. The comparison uses the server's household date, never the phone's date.
- The server returns `can_undo` on every care event, including sitter projections. The app shows Undo only when that flag is true. `undo-policy.ts` shares ownership, sitter batch restrictions, plant status and repot checks with the undo endpoints. Batch events use `/api/task-batches/:id/undo`. API tests cover every role and former family members returning as sitters with their own repot or rain history.
- `Today.svelte`: only owner/family sees task-view settings or repot-with-pot-details. The pot form follows the web's choices and positive whole-number/unknown size rule.
- The plant page and `completeAll`: Handle all uses the server command and completes only due/late tasks. The app shows it only to owner/family on Today, for plants with an open due/late row. It does not compute which tasks belong in the batch. Sitter plant headers come from allowed task/header endpoints; the forbidden plant-list/history endpoints are not called.

Successful care commands retain their returned event and next task in the cached row, as the web retains handled rows, so Undo does not disappear when Show completed is off. Undo and saved-view changes refresh from the server. Writes run only from an enabled user action. Failed writes are not automatically retried or saved for later; an explicit retry reuses its request UUID until success/undo. Nothing queues writes in Room or WorkManager.

Opening the app, returning to it, login and pull-to-refresh refresh both task views and the plant list. Network loss, DNS failure and connect/read timeouts mark data offline. The banner uses the device-local time of the last successful task refresh, `Offline, last updated HH:MM`, or `Offline, not updated yet` before the first refresh. Server failures and malformed responses instead show `Fytorium isn't responding. Showing data from HH:MM`, or `Fytorium isn't responding. Not updated yet`. Both states keep cached data and disable writes until a successful refresh. A connected transport alone cannot clear either state. WorkManager reads every six hours, with a six-hour initial delay, a connected-network constraint and a battery-not-low constraint. Logout/session loss cancels its unique job.

Thumbnails use a custom disk cache rather than another image dependency. Only `/api/photos/:id/thumb` is fetched, with a 1 MiB per-thumbnail read bound and a 50 MiB total cap. SHA-256 filenames avoid storing URLs. Reads update LRU timestamps; writes evict oldest entries until under the cap. Full-size photos, tokens and login codes never enter either domain cache. Bitmap decoding uses Android's built-in API. Cache, repository, logout failure, member-switch failure, in-flight logout, offline writes and eviction tests exercise their public interfaces against Room, disk and MockWebServer. WorkManager tests inspect its public WorkInfo constraints, period and cancellation.

## Dependency choices

All entries below are pinned in the version catalog. Android's built-in Keystore, clipboard and JSON API avoid extra storage and serialization dependencies.

| Artifact | Why it is here |
| --- | --- |
| Room runtime/compiler 2.7.1 | Transactional member-scoped offline snapshots, durable across process recreation |
| Kotlin kapt | Generates Room's DAO/database implementation; no runtime serializer dependency |
| WorkManager 2.10.1 | Durable six-hour refresh with network/battery constraints and logout cancellation |
| Custom thumbnail disk cache | Android bitmap decoding plus bounded, member-wiped LRU storage; avoids adding Coil for one thumbnail view |
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

## Plants and species

A05 adds native editing to the baseline cached Plants view described above. Plant forms use a Material 3 birthday picker and exposed choice fields, field-level server validation, a plant-settings overflow menu and an exact-name delete dialog. Species changes require a separate Save species action. Plant requests have their own reading, saving and error state; validated connectivity and session permissions gate writes, not Today or care refreshes. Background detail reads preserve enabled fields and unsaved input.

The Plants tab has a Material 3 app bar, text search by display/scientific name and the graveyard. Detail shows pot and placement attributes, schedules with Based on, care and species-change history, server-controlled Undo and Handle all. Add/edit includes the web's nickname, cultivar, birthday, health, site, pot, soil, size, growth stage, fertilizer and placement fields. Care settings support misting and per-task intervals. Restore requires a site; permanent deletion requires the exact display name. Species changes use the same search/confirmation flow as adding. Species names can be read, added, removed when owner-sourced and refreshed.

`/api/choices` serves the web's plant choice lists and name languages, instead of duplicating them in the app. English, Norwegian and Greek searches use `/api/species/search`; the app accepts `requires_confirmation` directly, without calculating match confidence or fuzzy distance. Sitters receive only shared active plant headers from `/api/plants`, including plants without upcoming tasks. Private history/settings/species calls and editing controls are absent. Allowed care actions remain on Today/Upcoming.

Plant detail JSON snapshots now also preserve complete attributes, care settings and species-change history. Room's table definition and schema version are unchanged. Offline reads use these snapshots and the existing thumbnail cache; no write is queued. Robolectric checks and light/dark references cover list, graveyard, add/edit, search/confirmation, detail/history, care settings, restore, repot, deletion confirmation and species names. `PlantsE2eTest` uses real OIDC and server APIs, adds through the native typo-confirmation flow, edits pot/placement, moves to the graveyard, restores and permanently deletes the synthetic plant. It cleans up only its exact synthetic fixture names.

## Android releases

Only tag pushes matching `android-vX.Y.Z` on `SpyrosPsarras/epaflix-fytorium` can run the release job. Pull requests and forks cannot sign or publish. Actions are SHA-pinned. Only that job has `contents: write`; checkout does not persist the token.

The tag supplies `versionName = X.Y.Z` and `versionCode = X*10000 + Y*100 + Z`. Components have no leading zeroes, Y and Z are at most 99, and versionCode must be 1 through 2100000000. CI queries all published Android releases, including prereleases, and refuses a code equal to or lower than any published code. Drafts do not count. Missing permissions or a failed query stop the job.

Before signing, CI runs `build`, `test`, `lint`, `screenshots` and `release-check` through `./scripts/android`. It then builds the R8-minified APK with the production key, verifies the APK's version and signing certificate with SDK tools, and writes `SHA256SUMS`. The GitHub Release contains `fytorium-X.Y.Z.apk`, `SHA256SUMS`, the version, the certificate fingerprint and an `android/`-only git changelog since the previous published Android tag. The first release includes all Android history.

Gradle reads `ANDROID_RELEASE_TAG` for the version. Release signing uses `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS`. The PKCS12 store and key passwords are the same. Without a keystore path, `release-check` still builds an unsigned APK. Debug builds never use the release signing configuration.

Keep the permanent key backed up in Vaultwarden at `/Fytorium/fytorium - android signing`. GitHub Actions uses `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS`. Never regenerate this key for an update, commit it, or put it in a build artifact. CI decodes it with mode 0600 into `$RUNNER_TEMP`, mounts that file read-only for signing, and deletes it even when building or verification fails. No Firebase secret is required by this release pipeline yet; A12 supplies optional Firebase configuration later.

The production signing certificate SHA-256 is:

```text
8A:A9:74:16:85:B7:03:12:75:7F:44:37:2E:B0:36:3B:16:F2:C6:A9:11:03:6C:EE:77:7A:9F:39:11:62:0C:F7
```

### Local dry run

```sh
./scripts/android release-dry-run android-v0.1.0
# Exercise the same monotonic-version gate without GitHub or credentials:
PUBLISHED_TAGS='android-v0.1.0' ./scripts/android release-dry-run android-v0.1.1
```

The Docker-only dry run validates the tag, generates a one-day throwaway PKCS12 key inside its container, builds the signed release APK, checks its version and certificate with `apksigner verify --print-certs`, and prints the checksum. It also confirms that the production fingerprint check rejects the throwaway key. The key is deleted on exit. Its APK and `SHA256SUMS` stay in the ignored `android/app/build/outputs/fytorium-releases/<tag>/` directory. Never publish or install it over a production build. Without `PUBLISHED_TAGS`, local validation has no published-release history; CI always queries GitHub. `./scripts/android test` also exercises tag bounds and non-increasing versions.

## Obtainium

1. Install Obtainium from its [GitHub releases](https://github.com/ImranR98/Obtainium/releases), then add an app with the GitHub repository as its source: `https://github.com/SpyrosPsarras/epaflix-fytorium`.
2. Leave **Include prereleases** off.
3. Set the APK filter regex to `fytorium-.*\.apk` so Obtainium selects only the Android release asset.
4. Obtainium does not show the signing certificate. Install [AppVerifier](https://github.com/soupslurpr/AppVerifier), then share or select the app in AppVerifier. Compare its signing certificate SHA-256 with the production fingerprint above. If checking an installed Fytorium app, do not open it or log in until the fingerprint matches. Allow Obtainium to check GitHub for updates only after verification.

For a PC check before installation, download `fytorium-X.Y.Z.apk` and `SHA256SUMS` from the same GitHub release into the repository root. Run these commands from that root, replacing `X.Y.Z` with the release version. `./scripts/android` builds the SDK image used here:

```sh
./scripts/android release-version android-vX.Y.Z
image="fytorium-android-$(printf '%s' "$PWD" | sha256sum | cut -c1-12)-sdk"
docker run --rm -v "$PWD":/work:ro -w /work "$image" \
  sh -ec '"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs fytorium-X.Y.Z.apk; sha256sum -c SHA256SUMS'
```

Compare `Signer #1 certificate SHA-256 digest` with the production fingerprint above, ignoring letter case and colon separators. Every pair of hexadecimal digits must match. `SHA256SUMS` checks the downloaded file's integrity; it does not replace the signing certificate check.

A certificate mismatch means the APK was not signed with the documented production key. A checksum mismatch means the download differs from the release checksum. In either case, do not install it, and tell the owner. If already installed, do not use it and uninstall it.

The web's personal settings at `/settings/notifications` link to GitHub Releases and this section. Owner, family and sitter roles can all reach that page.

## Sites

Owner and family can open More → Sites to list the server's plant counts, create and edit all site conditions, and delete with confirmation. Sitters never see the entry. Site data stays in memory while the screen is open; it is not stored in Room. Saves and deletes require a connection, and failed writes are never queued. Refresh sites recovers from a failed read without submitting a write.

The form uses the web's location, light and humidity choices and defaults, grow-light and draft switches, and four nullable temperature bounds. Validation and delete refusal messages come unchanged from the API. Light/dark screenshots cover the list, create/edit form, temperature conditions, form actions, delete refusal and offline form. HTTP tests check every request field, counts, validation, refusal and write blocking. The real-login emulator scenario creates a site, edits its conditions, seeds a plant through the API, checks its count and delete refusal, removes the plant, then deletes the site through the screen. Interrupted-run cleanup removes only its exact synthetic A07 fixture names.

## Bump the minimum app version

The server reads `MIN_APP_VERSION`. Unset or empty disables the minimum; invalid semver fails startup. No second app-version setting exists.

1. Publish the signed Android release and let Obtainium distribute it before requiring it.
2. In the homelab repository, set the Fytorium Deployment's `MIN_APP_VERSION` environment variable to the supported semantic version, for example `1.2.3`. Obtain owner approval for the Deployment change and apply it through the homelab's normal deployment process.
3. Check `/api/meta` while logged in. Its `minAppVersion` must report the new value. This endpoint and `/api/app/*` stay reachable by old apps. Other API requests carrying an older or invalid `X-App-Version` receive HTTP 426 with `{"error":"update_required","minAppVersion":"1.2.3"}`. Confirm the older app displays "Update Fytorium", while the new app and web still work. Web requests without the header are never refused.
4. If the release has a compatibility problem, lower `MIN_APP_VERSION` to the previous supported version and roll out the Deployment again. APK versionCode must still increase for any replacement release; never reuse an old tag or replace the production signing key.

## Species care

Plant settings → Species care shows every effective field, source, rule id and fetch time, source fetch/match status and outdoor-month provenance. Labels, input choices, JSON examples and estimate warnings follow the web care card. The server owns values and validation. Owner and family can override each field, remove an override with confirmation, refresh care data and retry Open Plantbook matching. More → Unmatched species shows the server's unmatched list and opens a care card or retries its match. Sitters see neither entry nor controls and send no species requests.

`SpeciesCareRepository` holds only route-local memory, with no Room changes and no queued writes. Controls use validated connectivity, member/role/update gates and this route's own saving/error state. Neither Today failures nor background reads disable an otherwise enabled editor. A failed write keeps the draft; Retry loading species recovers without submitting it. Successful commands refresh task provenance. `SpeciesCareScenario` uses real OIDC, native override/reset controls and the real HTTP schedule to prove that overriding watering to nine days changes the task's Based on source/value, then resetting restores the seven-day rule. It cleans up only its synthetic A10 fixtures and override. Accessibility checks and light/dark screenshots cover care, override, reset, offline controls and unmatched species.
