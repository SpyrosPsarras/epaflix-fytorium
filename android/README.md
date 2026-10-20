# Fytorium Android

Kotlin and Compose phone app, API 29 through 35. Login, Today and Upcoming use the real server. Today, Upcoming and previously loaded plant data stay readable offline. Plants supports native editing and lifecycle actions. Photo updates, the journal and photo identification use the same server endpoints as the web. Firebase Messaging delivers app notifications. No analytics or external crash service is included.

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

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Test, lint and emulator reports are under `android/app/build/reports/`. A debug-only network security config permits cleartext HTTP only for the exact local hosts `app`, `oidc` and the instrumentation-only fake receiver `fake-push`, without subdomains. Its base policy and the release manifest forbid cleartext. Production defaults to `https://fytorium.epaflix.com`; the Gradle property `serverUrl` overrides this build setting. Release builds reject non-HTTPS URLs before compilation. `release-check` assembles an unsigned R8-minified, resource-shrunk APK and reports its size. Compose, AndroidX, coroutines and OkHttp supply their consumer keep rules; app models use direct Kotlin access and built-in JSON, with no reflective serializer to keep. `release-launch` requires the running stack and installs the release APK using an ephemeral container-only signing key, checks the login UI and process, and rejects crashes. The e2e script builds with `http://app:3000`. Ordinary build/test/lint commands reset it to production.

## Emulator networking and ownership

The emulator container joins this worktree's Compose network. Inside the container, dnsmasq forwards guest DNS to Docker's resolver at 127.0.0.11. The guest emulator is started with that container's IP as its DNS server. It can therefore reach both canonical origins, `http://app:3000` and `http://oidc:8080`, without published ports, changed OIDC callbacks or host DNS edits. Before instrumentation, bounded readiness gates query dnsmasq directly for both names, check stack HTTP from the container, then issue HTTP requests to both names with `adb shell` inside the guest. Each gate has a configurable deadline and reports the failed probe and DNS logs. dnsmasq does not cache early negative answers. The emulator runs with `-feature -WiFiPacketStream`, which disables Netsim Wi-Fi packet streaming because it can strand guest DNS under CPU load. The emulator's own DNS proxy at 10.0.2.3 sometimes stops forwarding queries anyway: the guest gets "No address associated with hostname" while dnsmasq sees no guest queries. So after boot, `docker/e2e` adds guest-only `iptables` NAT rules that redirect 10.0.2.3:53 (UDP and TCP) straight to the container's dnsmasq. These rules need guest root (`su 0`), which the pinned Google APIs userdebug image provides. They exist only inside the disposable test emulator and never touch the app or the release build. The exact trigger inside the emulator is unproven. If readiness still times out, the gate prints the guest resolver state and the dnsmasq log. An instrumented test still asserts both endpoints respond from the guest, not merely from its container. adb binds to container-local `127.0.0.1:5037`, with inherited adb endpoints cleared and the fresh `emulator-5554` selected. The container uses bridge networking, not the host network, and mounts no host adb socket.

`./scripts/android e2e` requires a running stack from `./scripts/stack up`. It boots a fresh AVD with `--device /dev/kvm`, installs the debug APK and runs instrumented tests. The container runs as the caller's UID with the KVM device group, so caches remain caller-owned. The AVD is ephemeral, so the second run has no old browser cookies or device credentials. The script checks that Chrome is installed, selects it as the guest debug app so it reads test command-line flags, and disables first-run onboarding and the default-browser prompt. These settings exist only in the disposable AVD; they do not bypass OIDC or change the app's network policy. Boot waits are bounded. The emulator container has a unique name and is removed on exit, including failure. INT and TERM exit through cleanup. The stack is never started, rebuilt or stopped by e2e; it stays running for the next run.

The disposable AVD sets `captive_portal_mode=0`, then reconnects Wi-Fi and mobile data so new network monitors read it. Android skips its connectivity probes and treats any connected network as `VALIDATED`. Airplane mode still exercises offline behavior, but connected networks without validation are not covered by this e2e and rely on unit tests. External Google captive-portal probes cannot reliably finish on the private Docker network. The stack's `/healthz` returns a non-empty HTTP 200 JSON body, not the empty 204 expected by those probes, so pointing portal URLs there would misclassify the stack as a portal. No production device settings change. The container and guest must still resolve `app` and `oidc` and receive real HTTP 200 responses before instrumentation; airplane-mode recovery still checks validated connectivity, DNS and app HTTP.

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

The launch, guest networking and real Android Keystore round-trip tests always run. `E2E_LOGIN=1` also enables the Custom Tab scenario against the merged A01 login endpoints. It waits for the local provider in Chrome, enters and verifies subject `synthetic-a03-owner`, scrolls forward to Sign-in and waits for the redirect to return to Fytorium. It seeds a synthetic site and Testoria plant through the bearer API, advances the stack's `__test/clock`, completes a late watering task and undoes it, checks a future Upcoming row has no Snooze, then enables real guest airplane mode. Cached Today stays visible, writes are disabled and a disabled click produces no care event after reconnecting. After disabling airplane mode, a readiness gate bounded by `E2E_GUEST_NETWORK_TIMEOUT`, default 180 seconds, waits for the default network to be validated, `app` to resolve, and a fresh HTTP health request to succeed before the next scenario request. Only readiness probes retry, not repository refreshes or scenario API calls. Network loss evicts idle API sockets so requests cannot reuse connections aborted by airplane mode. API sockets also expire after one idle second, before the local server's five-second keep-alive timeout; transport retries remain disabled. The scenario deletes its plant/site, resets the clock, logs out, checks the local wipe and checks WorkManager cancellation. Interrupted-run recovery deletes only fixtures with this scenario's exact synthetic names. The phone viewport needs scrolling past the provider's claims textarea. Only forward swipes are used, since a scroll-to-beginning gesture can trigger Chrome's pull-to-refresh and erase the entered subject. No mocked token endpoint replaces the server. The Google APIs image supplies Chrome for Custom Tabs. The emulator stays on homePC, not GitHub CI.

## Today

Today has a centred title and a Today/Upcoming switch above the cards, without a greeting, daily tip or summary counts. Due dates and lateness remain server values. Empty sections are hidden. An empty day shows the next server Upcoming task, and an empty collection offers Add plant and owner-only Planta import. Sitters with no shares see a separate empty message.

Each care-type group is a rounded card, including when rows are sorted by plant or site. Row order within each card stays server-owned. Upcoming retains the server date groups and labels each row with its care type. Cover IDs come from TaskList and load only `/thumb` through the existing 50 MiB ThumbnailCache. Sitters retain their existing photo restrictions, so the server projects a null cover and the app shows an initial. A one-line pill overlaps the photo's bottom edge for pending late tasks: `7d late`, or `2mo late` from 60 days, using whole 30-day months. Snooze and circular completion controls stay on the row. Tapping the photo or name opens the plant page with that task's actions and Based on. Back returns to the selected Today/Upcoming view at the same scroll position. Owner and family see Handle all tasks at the bottom of each Today card with pending due or late tasks. It completes that care type across plants, follows the saved location filter and has one Undo for the whole batch. Upcoming and sitters do not show this button. Filter, sort and Show completed are in the filter FAB sheet. The list scrolls behind the FABs, with bottom padding to keep the last care action above them. Search opens the existing offline plant search, notifications open from the top bar, and feed customization stays in More. Add plant, import and notifications use their own request gates, never Today background loading or server errors.

Every API request sends the phone language in `Accept-Language`. The server selects plant names from nickname, common name in that language, English common name, then canonical name. Supported common-name languages are `en`, `nb` and `el`; `no` and `nn` map to `nb`, and other languages fall back to English. Today, Upcoming, Plants and the plant page use the same name. The plant page also shows the scientific name. Offline snapshots keep the last server name until a refresh.

`TodayPresentationTest`, `RepositoryTest` and `TodayNavigationTest` cover grouping, batch actions, cached reads and navigation. `TodayRedesignScreenTest` records seven light/dark variants under `app/src/test/screenshots/a15-*`: many synthetic covers with late badges, mixed groups, Upcoming, empty day, no plants, offline and sitter. The real-login scenario seeds eight synthetic plants and generated covers through the API, captures Today, Upcoming and the empty day in both themes, then removes only its named fixtures and restores settings. Captures are published to MediaStore Pictures in the disposable guest before Gradle uninstalls the APK and removes its app files. Publishing generated images requires no storage permission on API 29 or later. `./scripts/android e2e` pulls those real device captures into `.scratch/a15-emulator/`. No owner photos or Planta artwork are used.

## Photos, journal and identification

Plant detail → View all updates opens the gallery and its Add a photo update form. Journal shows all updates in the server's newest-taken-date order and links back to each plant. Both offer cover selection and confirmed deletion. Open original fetches into memory and discards it when closed. Only displayed gallery thumbnails use the existing 50 MiB cache. No original image, photo metadata or identification result is added to Room or a disk image cache.

Every photo input has Take photo and Choose photos. Take photo uses `ACTION_IMAGE_CAPTURE` with a non-exported FileProvider limited to `cache/captures/`. These files are temporary camera outputs, not cached photos. Replacing or removing a selection, successful upload, leaving the route and logout remove them. Application startup also deletes orphaned captures off the main thread, covering process death; new camera captures wait for that cleanup to finish. Rotation preserves a pending camera output. Choose photos uses AndroidX's Android Photo Picker contracts, single selection for updates and up to five for identification. The manifest requests the Play services Photo Picker backport for Android 10 through 12; devices without a picker use the contract's system document fallback. No storage or camera permission is requested.

`ImageDecoder` applies source orientation, scales the long edge to at most 2560 pixels and a fresh JPEG encoder at quality 85 strips EXIF, including GPS. Processing and uploads run off the main thread. The decoder reads directly from the content URI or capture file, using its target-size listener instead of buffering the original input. Unreadable images and memory exhaustion show a clear input error without blocking replacement or retry. Display previews decode in a background coroutine at the available width and a 400 dp height limit, with a loading placeholder. Encoded uploads still obey the server's 15 MiB per-image and 40 MiB identification limits. Multipart uploads use the process-owned authenticated client, retain refresh-on-401 behavior and report bytes sent. Sending all bytes is separate from the server accepting the upload. Failures retain the form and selected images for retry; nothing is queued.

Add plant and Change species both offer Identify from photos. Identification keeps one to five selected images, displays the server's ordered scores, GBIF confidence and remaining quota, and requires explicit confirmation before passing the server search receipt to the existing plant form. Health choices and today's date come from `/api/choices` and `/api/meta`. Photo and identification controls use validated device connectivity, session/member/role gates and their own saving and error state. Care refreshes and care-server failures do not disable them. Sitters never call photo, journal or identification endpoints.

The A06 emulator scenario runs inside the real-login test when `E2E_LOGIN=1`. `photos/PhotoScenario.kt` generates a solid-colour synthetic 3200×1200 PNG in the target app's temporary FileProvider directory. An instrumentation activity monitor returns its content URI as `RESULT_OK` to the actual Photo Picker contract. A second monitor intercepts `ACTION_IMAGE_CAPTURE`, checks its private output URI and writes a synthetic JPEG there before returning `RESULT_OK`. Neither hook exists in the production app. Upload, cover, journal, the fake Pl@ntNet request, mandatory confirmation and adding the identified species all go through the real app and API. Fixture recovery and cleanup match only A06's exact synthetic names.

Repository tests cover scaling, EXIF rotation/GPS removal, multipart fields and progress, invalid/unreadable images, server errors, sitter/offline guards, identification scores/quota and uncached originals. Cleanup tests cover startup and logout without deleting unrelated cache files. JVM and device tests generate a 144 MB, 48-megapixel BMP one scanline at a time, then check URI upload and display-size decoding. Robolectric's Linux native decoder cannot decode file descriptors, so JVM tests use a test-only memory-mapped source adapter; device tests exercise the real content-resolver source. Compose accessibility tests and 20 light/dark reference images cover the gallery, journal, original, form, date picker, deletion dialog, failure, upload, suggestions and confirmation.

## Planta import

More → Import from Planta is owner-only. Enter a one-time Planta App Portal code and fetch. Cards marked "Needs review" appear first, show the Planta cover and ask "Which picture looks like your plant?" Tap a reference-photo tile labelled "This is my plant" or the visible **Skip plant** button. Each tile is one tap target. **Search for another species** opens search on review cards; search starts open when no candidates exist. A photo suggestion may label a tile "Looks most like your photo" or offer "Not sure: import as <Genus> (any kind)". Exact matches show a large green check and "Ready to import". **Change species or skip** opens optional search and skip controls on confirmed cards. Both review summaries list tappable plant names that scroll to the card. Skipped cards say "Skipped. This plant will not be imported." **Undo skip** opens candidates and search; choosing a species restores the plant. Then import confirmed plants. The review shows the Planta cover, reference photos with credit, pot, soil, last care dates and the server's cover status. Re-import updates Planta-sourced fields, including owner edits, while retaining local settings; new sites need their conditions completed in Sites.

The code stays only in an unsaved password field until submission, which clears it before the request, including on failure. It never enters Room, preferences, saved state or diagnostics. Code-exchange errors never relay upstream response text. Staging, selections and commit eligibility come from `/api/import/planta`; opening or restoring the route fetches that review without another code. Import controls use validated connectivity, the owner/session gates and the import's own request state, not Today background refreshes.

`planta/PlantaScenario.kt` runs inside the single real-login test. It uses only the stack's fake Planta, restores the review across Activity recreation, confirms the fuzzy Testoria match, imports its cover and checks the plant in Plants. It deletes only the fake import's exact synthetic plant/site names before and after each run, so repeated runs on one stack start a fresh review. Repository/state tests, saved-state tests and 18 accessible light/dark snapshots cover the code, review, search, confirmation, offline/error states and cover results.

## Push notifications

More → My notifications uses the same reminder time and daily, weather and completion switches as the web. Time is the household's time zone. Sitters do not get weather controls. The device list includes browser and Android devices, with independent enable and remove controls. Its network and error state is independent of Today background reads.

Firebase Messaging 24.1.1 is the only added runtime dependency. It supplies token rotation and `FirebaseMessagingService`; notification construction uses AndroidX already in the app. There is no Firebase Analytics dependency. `FirebaseInitProvider` reads four resources extracted from the matching client in `google-services.json`: `google_app_id`, `gcm_defaultSenderId`, `project_id` and `google_api_key`. Gradle extracts these directly instead of adding the Google Services build plugin. No real JSON file is written to the checkout.

The app registers the FCM token with its bearer identity after login, on token rotation and on foreground entry. The server binds it to the authenticated app device, never a client-supplied member or device ID. Token rotation preserves the device's disabled state. Logout, refresh-token replay and member removal revoke the device and delete its FCM registration. Tokens never enter diagnostics or the device-list response.

Data messages carry the same `title`, `body`, `tag` and `url` as Web Push. The app posts in `daily_reminder`, `weather_alert` or `completion_notice`. The stable `fytorium-daily` tag replaces the previous reminder. There are no action buttons. A tap always returns to Home, even from a nested More screen. Receiving a message refreshes current data and schedules a network-constrained cache refresh. Messages received without a session are discarded.

On Android 13+, the Enable notifications button explains the categories before opening the system permission prompt. The app records that attempt before launching it. After denial it only links to Android notification settings, never asks again automatically. Older Android versions use the same button to open system settings.

### Synthetic tests and release config

`app/src/debug/google-services.json` is an obviously synthetic placeholder, not a Firebase project. Debug builds disable Messaging auto-init and use a debug-only token source that never requests a Google installation or token. Instrumentation can inject a synthetic token and replay a `RemoteMessage` through the exact handler used by `onMessageReceived`. There is no exported receiver or intent for either operation, and these classes are absent from the release source set.

The release build requires the **raw JSON contents**, not base64, in the `GOOGLE_SERVICES_JSON` environment variable. Tagged CI passes the same-named GitHub Actions secret in memory. Missing or malformed config fails the build, including with warm Gradle outputs. A synthetic project is refused by normal releases. `./scripts/android release-check` first proves missing config is rejected, then supplies the placeholder only for its minified APK check. It checks R8's mapping, dex and manifest for the debug bridge. `release-dry-run` uses the same placeholder with a throwaway signing key. Neither command contacts Google. Never distribute these test APKs.

The fake push service generates an ephemeral RSA service account on startup and verifies the server's signed OAuth JWT. No key is committed or baked into an image. API tests exercise data-only payloads, invalid-token removal, 429/5xx lease retries, OAuth concurrency/expiry caching, ownership and revocation. The A12 scenario runs inside the single real-login test. It registers a unique synthetic token, enables Android permission through the explained user action, runs the real server reminder worker, reads the recorded message from `http://fake-push:8080/fcm/received`, and calls the shared production handler. It asserts the real system notification's channel and replacement, taps it with UiAutomator, and checks Home opens. **The emulator did not receive a message through Google's network.** It received a replay of the fake's recorded server message. A test-clock-only endpoint clears only the signed-in member's current-day reminder lease so this scenario is rerunnable on one stack. Twelve accessible light/dark reference images cover settings, devices, permission-state wording, offline/error states and background reads.

### Owner Firebase setup

Run `./scripts/firebase-setup` on the owner's desktop. It walks through project creation with Analytics off, Android app registration for `com.epaflix.fytorium`, FCM HTTP v1 enablement and service-account key download. It stores both files as Vaultwarden attachments under `/Fytorium/fytorium - firebase` and the app config as GitHub secret `GOOGLE_SERVICES_JSON`. Vaultwarden hub access uses the desktop login-keyring item `service=mcp-hub`, `key=api`, never a dotfile or chat value. The wizard then asks for the homelab checkout and its existing encrypted Fytorium Secret under `2-k3s/25.fytorium`, prints the exact `sops-kpx` edit command and Secret/Deployment fields, and requires confirmation before offering to delete the downloads. It never deploys or prints key/config contents. Owner project creation and real-phone delivery remain A14 work.

Server configuration:

| Variable | Meaning |
| --- | --- |
| `FCM_PROJECT_ID` | Firebase project ID. Required to enable FCM. |
| `FCM_SERVICE_ACCOUNT_FILE` | Absolute container path to the mounted service-account JSON file. |
| `FCM_SERVICE_ACCOUNT_JSON` | Alternatively, raw service-account JSON from a secret environment variable. Takes precedence over the file. |
| `FCM_BASE_URL` | HTTP v1 origin, default `https://fcm.googleapis.com`. |
| `FCM_OAUTH_TOKEN_URL` | OAuth token endpoint, default `https://oauth2.googleapis.com/token`. |

Without a project ID and account, app devices are skipped with one process-level message. Web Push keeps working. The local Compose stack sets both endpoint URLs to fake-push and project `synthetic-fytorium`; test-clock requests reject non-local FCM endpoints. Production should leave both URL overrides unset. The wizard's SOPS instructions store `FCM_SERVICE_ACCOUNT_JSON` and `FCM_PROJECT_ID`, or mount the JSON and use `FCM_SERVICE_ACCOUNT_FILE`. Migration `20261013120000_fcm.sql` extends notification subscriptions with transport, enabled state and a member-bound app-device reference, and deletes registrations when app devices are revoked.

## Login and diagnostics

Login opens `/auth/login` with `app=android`, the fixed `com.epaflix.fytorium:/oauth2redirect` URI, S256 challenge and a fresh 256-bit state. The app checks the exact hostless URI, state, duplicate query parameters and a ten-minute local login-attempt expiry. A validated redirect consumes the attempt before exchanging a code. Invalid or stale redirects cannot consume a newer pending attempt. Redirects received after login are ignored with a predefined debug-only logcat event, not a user error. The server's one-time code expiry remains 60 seconds.

Both tokens and the pending verifier/state are AES-256-GCM encrypted with an Android Keystore key. Pending state survives process recreation. No backup or device transfer may carry credential files. All API requests use a bearer token when logged in. The Application owns the API client, credential store and SessionController. SessionController exposes loggedIn, busy, message and updateRequired as a StateFlow, deriving loggedIn only from the credential store token flow. Login completion, version checks, refresh and logout run in its process-scoped coroutine scope. Its redirect queue and in-flight exchanges survive Activity recreation; Activities only forward fresh intents and render session state. Code and refresh exchanges use a separate HTTP client with no idle pooled connections. The browser round-trip can outlive the startup API socket, and these single-use requests must not automatically replay after a broken connection. Ordinary API calls still use their connection pool. A process-wide lock serializes refreshes across clients, re-reads the store, and reuses tokens already rotated by another caller. A 401 triggers at most one refresh and one replay. Only an explicit `invalid_grant` or `invalid_token` refresh rejection clears credentials and returns to login. Rate limits, server failures, malformed responses and network errors retain tokens and show a retry message. A retry sends the same stored refresh token after a lost reply, matching login contract Amendment 1. Restored Activities skip the old redirect intent; fresh redirects still run through `onNewIntent`. Logout calls the server and clears local credentials even if the network fails. Session loss also clears the member-scoped Room and thumbnail caches and cancels background refresh.

At startup, and again after login, `/api/meta` gates on 426 or a newer semantic `minAppVersion`. A missing endpoint, 404, remains compatible with older servers. The real meta response also carries serverVersion, today, timezone and role; a null minAppVersion imposes no minimum. The request sends `X-App-Version`. Network failures show a retry message instead of an update requirement.

More → About shows the build's version and copies at most 64 predefined error events, bounded to 8 KiB. Exceptions, intent URLs, response bodies and user input cannot enter that log. The crash handler records only APP_CRASH and terminates without logging throwable text. No tokens, authorization codes or verifiers enter diagnostics.

## Theme and tests

The app uses a green palette in both themes. Light mode uses background #f7faf0, text #1a1d17 and primary #3f6a2a. Dark mode uses background #12150f, text #e1e5d8 and primary #b5d99c. Today completion circles and snooze controls use the primary colour. The system setting chooses the palette; dynamic device colours are disabled.

MockWebServer tests cover RFC 7636 PKCE, state/URI rejection, contract exchange, single-use state, fresh connections for code and refresh exchanges, refresh rotation, concurrent requests, explicit refresh rejection, retryable failures, second 401, logout and version checks. Source-policy assertions and device tests restrict debug cleartext; Activity recreation tests check process-owned client/store identity, queued redirects during a blocked version check, and token responses arriving after recreation. Duplicate and stale-then-valid redirects are covered, as is the process-restore replay guard. Robolectric tests exercise every shell screen with Compose accessibility checks and explicit 48 dp target/4.5:1 contrast assertions.

Checked-in Roborazzi reference PNGs cover the shell screens, populated Today, Upcoming, offline Today, sitter Today/Upcoming and cached plant list/detail at 360×800 in both themes. Task screen tests check selected tab semantics, labelled settings and the single-target Show completed toggle, per-type handle-all visibility, server-controlled Undo, offline/server-error banners, disabled controls and accessibility. Plant page tests cover tapped-task actions, sitter visibility, provenance and pot-form validation. `screenshots` verifies them. Deliberate UI changes update references with `ANDROID_RECORD_SCREENSHOTS=1 ./scripts/android screenshots`. Review the changed PNGs before staging. All generated test outputs stay in ignored build directories.

## Today, Upcoming and offline reads

`Repository` uses the one process-owned `ApiClient` for every API request, including thumbnails. JSON response models read the OpenAPI field names directly, without reflective serialization. Room stores member-id/resource-keyed JSON snapshots for Today, Upcoming, the plant list and plant details with history and schedules. Snapshots preserve the server's sections, ordering and provenance. Details load when opened, not for every plant during background refresh. Logout clears both caches even if revocation fails. A member or role change wipes the old rows, thumbnails and visible state before fetching the new member's data. A generation check prevents in-flight reads from restoring data after logout. Cache files are excluded from cloud backup and device transfer. Room exports its schema to `app/schemas/` and discards incompatible cache schemas with destructive migration.

Today uses a centred Material 3 app bar, Today/Upcoming tabs and pull-to-refresh. The filter FAB opens a bottom sheet with labelled Sort and Filter controls and a single toggleable Show completed row. Rows offer Done, Snooze 2 days and server-allowed Undo. Tapping a row opens the plant page with Skip, Repot with new pot and soil, and Based on where allowed for that task and role. Sitters opening from Today or Upcoming see the tapped task without owner-only sections. Based on shows input, value, adjustment, source, rule id, factor, fetched time and reason where supplied. Today groups rows by care type; Upcoming keeps the server date sections. Both use the saved task-type/plant/site sort, all/indoor/outdoor filter and Show completed preference. These preferences are saved through `/api/tasks/settings`; no settings write is cached or queued. The view metadata inside a task snapshot describes that saved view.

Undo permission comes from the server. The remaining action visibility follows these web rules:

- `CareTask.actions()`: Done and Skip for applicable rows; Snooze only when `status == due` and `dueOn <= list.today`. Skip appears on the opened plant page. The comparison uses the server's household date, never the phone's date.
- The server returns `can_undo` on every care event, including sitter projections. The app shows Undo only when that flag is true. `undo-policy.ts` shares ownership, sitter batch restrictions, plant status and repot checks with the undo endpoints. Batch events use `/api/task-batches/:id/undo`. API tests cover every role and former family members returning as sitters with their own repot or rain history.
- Only owner/family sees task-view settings or the plant page's repot-with-pot-details form. The pot form follows the web's choices and positive whole-number/unknown size rule.
- Today's card-level Handle all tasks calls `/api/tasks/:taskType/complete-all` for owner/family when a card has pending due or late tasks. The server follows the saved all/indoor/outdoor filter, and one Undo reverses the whole care-type batch. The plant page keeps its separate per-plant Handle all tasks command at `/api/plants/:id/tasks/complete-all`. Both complete only due or late tasks. The app does not compute which tasks belong in either batch. Sitters use the allowed plant-list/header and task endpoints, without private plant fields or history.

Successful care commands retain their returned event and next task in the cached row, as the web retains handled rows, so Undo does not disappear when Show completed is off. Undo and saved-view changes refresh from the server. Writes run only from an enabled user action. Failed writes are not automatically retried or saved for later; an explicit retry reuses its request UUID until success/undo. Nothing queues writes in Room or WorkManager.

Opening the app, returning to it, login and pull-to-refresh refresh both task views and the plant list. Network loss, DNS failure and connect/read timeouts mark data offline. The banner uses the device-local time of the last successful task refresh, `Offline, last updated HH:MM`, or `Offline, not updated yet` before the first refresh. Server failures and malformed responses instead show `Fytorium isn't responding. Showing data from HH:MM`, or `Fytorium isn't responding. Not updated yet`. Both states keep cached data and disable writes until a successful refresh. A connected transport alone cannot clear either state. WorkManager reads every six hours, with a six-hour initial delay, a connected-network constraint and a battery-not-low constraint. Logout/session loss cancels its unique job.

Thumbnails use a custom disk cache rather than another image dependency. Only `/api/photos/:id/thumb` is fetched, with a 1 MiB per-thumbnail read bound and a 50 MiB total cap. SHA-256 filenames avoid storing URLs. Reads update LRU timestamps; writes evict oldest entries until under the cap. Full-size photos, tokens and login codes never enter either domain cache. Bitmap decoding uses Android's built-in API. Cache, repository, logout failure, member-switch failure, in-flight logout, offline writes and eviction tests exercise their public interfaces against Room, disk and MockWebServer. WorkManager tests inspect its public WorkInfo constraints, period and cancellation.

## Household management

More opens native Household settings, Members and Custom tasks screens. Sitters see none of them. Members is owner-only. Family members can open Household settings and Custom tasks, edit their feed and custom tasks, and read household location and misting. Only owners can change household values, invite or remove members, change roles and edit sitter shares. Personal notification controls belong to A12.

Each route owns an in-memory repository, discarded on exit or member/role change. Controls depend on validated connectivity, the current member/role and update gate, and that route's loading, saving and last error. Today refreshes and failures do not disable these controls. A failed request keeps unsaved input; an explicit successful Refresh clears the route's write block. Nothing is queued or stored in Room.

Household settings mirror the web's four feed switches, nullable latitude/longitude with optional label, and household misting switch. Custom tasks select a plant or site. Graveyard plant definitions remain editable as on the web, although they produce no reminders. Names, whole-day intervals and immutable targets use server validation. Deletes, member removal and invite revocation use Material 3 AlertDialogs. Role choices and removal buttons use the members API's `role_options` and `can_remove`, not locally inferred protection rules. Owner roles are protected; owners can change other members between family and sitter. Changing to family clears plant shares. The web uses the same API capabilities.

An invite chooses family or sitter and, for a sitter, shared active plants. The link lives only in transient Compose state until Back, Hide link or leaving the route. Sharing uses `ACTION_SEND`, `text/plain` and Android's chooser. Invite links, tokens and API response bodies never enter diagnostics, Room or saved state. Acceptance remains in the browser.

`E2E_LOGIN=1 ./scripts/android e2e` includes A09. The owner holds a separate bearer API session and creates an invite through the household repository for one of two synthetic plants. A new synthetic OIDC subject accepts in a real Chrome Custom Tab, then logs into the real app through another Custom Tab. The app shows only the shared plant and hides household controls. The owner's API session removes the subject; the app's next refresh returns to login and clears its member cache. Each run uses a UUID-suffixed second subject. After the owner's API observes invite acceptance, the scenario also waits for Chrome to render the authenticated sitter page, then clears only this disposable guest's Chrome profile before app login. The next Custom Tab has no remembered subject, cookies or scrolled provider form. The guest's debug-app selection and onboarding-disable command-line flags survive this reset. The owner keeps its separate API session, and the same unique sitter subject must log into the app through real OIDC. A committed member row alone does not prove that Chrome received the callback's deletion of the single-use invite cookie. Auth-cookie deletion uses the same configured transport options as creation: SvelteKit's default Secure deletion is ignored on the isolated HTTP origin, leaving a consumed invite that makes the next app login return `not_member`. HTTPS cookies remain Secure. Force-stopping Chrome can discard recent cookie writes and hide this defect, while merely closing the tab can retain an off-screen provider form. The explicit test-profile reset isolates the flows; HTTP header regression tests separately require the callback to clear its cookies correctly. It closes the idle API actor's connections before removal, without retrying a mutation. Fixtures and devices are cleaned up, and the owner session is restored for the existing care scenario's logout. No identity is injected into server tables and no token endpoint is mocked.

Repository tests exercise server capabilities, invite secrecy, household permissions, location removal, plant/site task payloads and server validation. Compose tests cover role gates, care-state independence, confirmation before removal, offline writes, accessible controls and light/dark screenshots of lists, forms, links and destructive dialogs.

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

## Home feed and weather

Home feed sits above the Today tasks, with horizontal native Material 3 cards. This keeps the existing bottom navigation and Upcoming tab unchanged. Feed cards use the server results, grouped in the same order as `HomeFeed.svelte`: late care and current weather, weather alerts, then plant warnings. The app displays warning inputs and hardiness sources without computing weather or plant rules. Only the newest visible rain card carries mark-watered and undo controls. Rain writes retain their request UUID for an explicit retry; only HTTP 409 with `rain_report_not_ready` says "Try again in a moment". Successful rain writes also refresh Today and Upcoming.

Customize my feed and More → Feed and weather settings open the same screen. Both owner and family can save their own feed preferences and read the household location. Only the owner can change or remove that location, matching the web and the existing API policy. Sitters see only server-supplied late-task cards, without feed or weather settings. A member or role change hides the previous feed immediately.

Feed data stays in memory, never Room. A failed read keeps the loaded cards. No validated network means offline; timeouts, server errors and malformed responses on a validated network show "Fytorium isn't responding". Reads never disable forms or rain actions; only role, offline state and a pending write do. Location and settings writes are never queued. Foreground, pull-to-refresh and background care reads also refresh the feed. Home polling runs in the repository only while the Today view is selected on the Today tab and the activity is at least STARTED. It stops in Upcoming, feed settings, other tabs and offline. A configured location polls every 3 seconds before its first forecast for at most 20 attempts per session, then every 30 seconds. Server failures also back off to 30 seconds without stopping polling. Slow feed reads do not block the next task refresh.

Repository and Compose tests cover rain retries and undo, offline writes, saved preferences, owner-only location writes, sitter visibility, logout during a read, role changes, labels and 48 dp controls. Twenty-two additional light/dark reference PNGs cover the feed and settings. With `E2E_LOGIN=1`, the emulator also seeds one synthetic outdoor plant, runs the fake-weather worker, checks its cold alert, marks watered after rain and undoes the batch. It verifies both commands through plant history, removes its fixtures and restores the saved preferences and location before the existing care scenario.

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
