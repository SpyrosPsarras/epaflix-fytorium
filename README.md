<img src="assets/icon.svg" width="64" height="64" alt="">

# Fytorium

Self-hosted plant care for one household. SvelteKit serves the UI and HTTP API in one image.

## Develop

Only Docker and a POSIX shell are needed on a Linux host. The test wrapper uses host networking and mounts the Docker socket so Testcontainers can start Postgres on a random port.

```sh
./scripts/container install --frozen-lockfile
./scripts/container typecheck
./scripts/container test:unit
./scripts/container test:api
docker build -t fytorium:walking-skeleton .
./scripts/smoke-image fytorium:walking-skeleton
```

API tests run real migrations and a synthetic OIDC provider, including PKCE and signed ID tokens. Table-driven unit tests exercise the pure schedule engine with explicit calendar dates, without a database or clock.

## Run the stack and browser tests

```sh
./scripts/stack up
./scripts/stack test
./scripts/stack test --screenshots
./scripts/stack logs app
./scripts/stack down
```

Tests install dependencies in Docker, then run in the matching official Playwright image with a Pixel 7 touch viewport. Screenshots, video and traces go to the gitignored `test-artifacts/` folder on failure. `--screenshots`, or `PLAYWRIGHT_KEEP_ARTIFACTS=1`, keeps all three for passing tests too. Pass a test filename to run one scenario.

The stack has no host ports by default. `STACK_APP_PORT=3000 ./scripts/stack up` opts into an app port for HTTP inspection. Browser login runs inside the stack, where both the app and browser resolve `http://oidc:8080/default`. Chromium treats `http://app:3000` as secure for the service worker assertion, without changing the deployment config.

The clock endpoint `/__test/clock` accepts JSON `{"action":"set","at":"2030-01-01T00:00:00Z"}`, `{"action":"advance","milliseconds":86400000}` or `{"action":"reset"}`. GET reads the current time. It requires both `FYTORIUM_TEST_CLOCK=true` and `NODE_ENV=test`, and production refuses that switch. Server code uses `src/lib/server/clock.ts`; OIDC signature timestamps still use the provider's real clock. `tests/browser/helpers/ntfy.ts` reads actual topic deliveries with ntfy's JSON poll API.

WireMock mappings in `tests/stack/mappings/` serve synthetic placeholders through seven service aliases. Add endpoint mappings there as the API adapters arrive. MinIO and `mc` build from pinned upstream source tags because their published images are unavailable. `down` removes the disposable stack data.

Optional real APIs: `./scripts/stack-real-apis` uses the owner's existing Vaultwarden runner, requiring Python 3 on the host. It injects only Pl@ntNet and Perenual keys into the app environment and changes those two base URLs. It never saves keys. Do not use this for normal tests; Perenual's quota is 100 requests per day. Open Plantbook needs credentials that are not yet in Vaultwarden; the owner will confirm the Vaultwarden path. This script does not enable real Open Plantbook calls.

## Sites

After login, open **Sites** from the home screen to create, edit or delete a site. The list counts its plants. Deletion requires confirmation and refuses sites that still have plants.

The UI and API tests use the same JSON endpoints: `GET /api/sites`, `POST /api/sites`, and `GET`, `PUT`, `DELETE /api/sites/:id`. POST and PUT take all site conditions using the vocabulary in `CONTEXT.md`: `name`, `location`, `light`, `grow_light`, `humidity`, `draft`, `warm_min_c`, `warm_max_c`, `cold_min_c`, `cold_max_c`. Temperature bounds may be omitted or null. PUT replaces conditions rather than patching them. Invalid fields return 422 with an `errors` object keyed by field; malformed JSON returns 400. Deletion returns 204 or 409 with a message saying how many plants to move or remove. Every endpoint and page requires a household session.

## Plants

Open **Plants** from home, then **Add plant**. Search by name, choose a candidate and confirm uncertain matches before filling in its site, optional nickname, cultivar and birthday, pot and plant attributes. The plant page links to its settings and to the site's plant list. Site settings are now at /sites/:id/edit. Plant lists filter by display name or scientific name and show the chosen cover photo, or a placeholder.

API endpoints: `GET /api/species`, `GET /api/species/search?q=...`, `GET /api/plants` with optional `site_id` or `count=true` for a count only, `POST /api/plants`, and `GET` or `PUT /api/plants/:id`. Search returns `search_id` and candidates. POST carries `search_id`, the chosen `gbif_key`, `confirmed: true` when required, and the plant attributes. PUT replaces the attributes without changing the species. Every page and endpoint requires a household session.

Search merges the multilingual name index with stored scientific and canonical names, deduplicated by GBIF key. Exact stored species-rank scientific names take priority. In the scientific-name path, only a full name match, ignoring case and surrounding spaces, skips confirmation locally. Partial local hits require confirmation and carry no match type or confidence. Unless a local exact species-rank hit needs no confirmation, the GBIF adapter also calls species/match with kingdom=Plantae and verbose=true. GBIF candidates appear alongside local partial scientific-name hits, which remain available during an outage. Name-index hits report matched_name and matched_language; only an exact normalised match to exactly one species-rank taxon skips confirmation. Species use the accepted GBIF key for synonyms. If the match omits an accepted name, the adapter fetches species/{acceptedUsageKey}. Both requests share a three-second timeout. GBIF names are attributed under CC BY 4.0.

The server retains search results for five minutes, with a maximum of 1,000 records per process, scoped to the household. After expiry or restart, search again. EXACT species-rank matches with confidence at least 90 need no confirmation. Other match types, lower confidence, lower taxon ranks and alternatives require confirmed=true for that exact chosen key. NONE and taxa outside Plantae or above species rank are refused. Client-sent taxonomy is ignored.

All plant fields use the vocabulary in CONTEXT.md. POST and PUT validate on the server; field errors return 422, malformed JSON returns 400, and GBIF outages return 503. Birthday uses the server clock's UTC date. The database checks birthday against the recorded validation date so the test clock and database do not disagree. Enum, positive-integer and household-site constraints also live in Postgres. Occupied-site tests now create plants through the API, and the phone scenario adds one through the UI.

## Species names

The plant page links to /species/:gbifKey. It groups names by language and shows each name's source, GBIF CC BY 4.0 attribution, and a CC0 note when Wikidata names are present. The owner can add or remove names and queue an external-name refresh. Names accept 1–200 characters and languages en, nb, el or la. Owner names survive refreshes. The name index is instance-wide, like the species catalogue; reading it needs a household session and editing names needs the owner role.

Postgres must provide pg_trgm and unaccent, and the migration role must be allowed to create those extensions. postgres:17-alpine and production CNPG include both. The migration creates a generated lower-case, accent-insensitive name and a GIN trigram index. Search ranks similarity with a prefix boost, without fetching external names on the search path. The five-minute server-side search record still enforces confirmation when adding a plant.

First storage indexes the canonical name as Latin from GBIF and queues background filling from GBIF vernacular names, Wikidata labels and aliases found by GBIF property P846, and exactly matched NorTaxa taxa. Each HTTP request has a three-second timeout. Successful sources replace only their own vernacular names; failed sources keep their cached names. A persisted row per species and source records completion, failures, retry time and a 30-second lease. The worker checks every five seconds and retries only failed sources, starting after one minute and doubling the delay up to 24 hours. After eight failures that source stops until an owner refreshes names. Refresh resets all three sources without deleting cached or owner names. Refreshes do not block plant adds. GBIF and Wikidata names are cached under CC BY 4.0 and CC0. NorTaxa's licence remains unverified, so keep its runtime cache private. All committed fixtures are synthetic.

GET /api/species/:gbifKey reads a species and its names. POST /api/species/:gbifKey/names accepts {"name":"Trialleaf","language":"en"}; DELETE /api/species/:gbifKey/names/:id removes an owner name only; POST /api/species/:gbifKey/names/refresh returns 202 after queuing work. Writes reject foreign origins. WIKIDATA_BASE_URL defaults to https://www.wikidata.org/ and NORTAXA_BASE_URL to https://nortaxa.artsdatabanken.no/. These base URLs, and GBIF_BASE_URL, are overridden by in-process fakes for API tests and WireMock for browser tests.

## Care tasks

Home shows **Today** when plants exist, grouped by task type with late badges. Tap **Done** once to record any task and see the next date; the same row offers **Undo**. Water, Fertilize, Mist, Clean leaves, Remove dead flowers and leaves, Seasonal cut-back and Repot share the same completion path. Plant pages show the next due date for each task, a **Based on** panel with input values, factors and sources, and care history with the member's OIDC name or email, falling back to "Member". Undone events remain marked in history.

The pure engine in `src/lib/schedule/engine.ts` takes a care profile with sources, site, pot, plant, per-task history, custom intervals, today, added date and optional weather. Outdoor watering uses the cached weather snapshot for temperature and daylight adjustments. The single `taskTypes` list and per-task rules in `src/lib/schedule/rules.ts` define labels, defaults, factors, applicability, anchoring and seasons. Watering starts at seven days, applies site, pot, plant and anchor-month factors, rounds to whole days and clamps to 1–60 days before custom intervals. The northern-hemisphere month table stretches winter watering. Fertilizing defaults to twice the current watering interval, with longer defaults for sticks and slow-release, and shifts out-of-season dates into March–September. Pruning defaults to March. Full defaults and terminology are in `CONTEXT.md`. These are starting estimates, not a substitute for checking soil moisture.

Dates use `HOUSEHOLD_TIMEZONE`, default `Europe/Oslo`. Server reads use the shared clock and Intl to resolve instants to calendar dates before calling the engine. Watering with no effective completion is due on the plant's added date. Other interval tasks start at added date plus interval. Otherwise, the last non-undone completion plus the current interval determines the next date, subject to seasonal and pruning rules. No due date is stored.

API endpoints: `GET /api/tasks?view=today` returns dated task groups, `GET /api/plants/:id/tasks` returns the plant's schedule, and `GET /api/plants/:id/history` returns all care events, including marked undone entries. `POST /api/plants/:id/tasks/:taskType/complete` takes `{"request_id":"<client UUID>"}`. Retry the same action with the same UUID. A reused UUID for another action returns 409. `POST /api/care-events/:id/undo` marks that event undone; a second undo returns 409. Both commands return the event and recomputed task. Every endpoint requires a household session; foreign plants and events return 404. Cross-origin writes are refused.

Plant settings allow a custom interval per task type, set or cleared, and a misting switch. Custom intervals are whole days from 1–365 and override only the interval, not applicability or the fertilizing season. `GET` and `PUT /api/plants/:id/care-settings` use `{"misting_enabled":true,"customIntervals":{"clean_leaves":30}}`. PUT replaces the interval map; omit a task key to clear its override. Unknown types, fractional or out-of-range intervals and non-boolean switches return 422; malformed JSON returns 400.

**Household settings** on Home controls misting for all plants. `GET` and `PUT /api/household/settings` use `{"misting_enabled":true}`. Household and plant switches both default on; both must be on for indoor misting to apply. These endpoints require a session, reject cross-origin writes and scope settings to the session's household. All members may edit until roles are enforced in #16.

## Task handling

Today and **Upcoming** offer Done, Snooze 2 days and Skip for each task. Snooze and skip write care events, not completions: due becomes the action date plus two days or the current interval respectively. They override seasonal dates as explicit postponements. Not-applicable tasks refuse these commands. Undo ignores the event and restores the previous schedule. The reserved engine field `history[type].snoozedUntil` carries the snooze action's calendar date; the generic history step adds two days. The tasks module resolves event timestamps and sequence order first, so a later completion supersedes an earlier snooze or skip even on the same day.

Upcoming covers fourteen household calendar days including today. Overdue tasks join today's group. Each task appears once, at its next due date. Sort by task type, plant or site, filter all/indoor/outdoor and show completed using **Save task view**. Preferences are stored per member in Postgres and applied by the server. Showing completed adds today's non-undone completions with Undo, including after a reload or new session.

The plant page offers **Handle all tasks**. One request and one transaction complete exactly the tasks due or late today. All its events share the `care_actions.request_id` as `care_events.batch_id`; Undo on any of them calls `POST /api/task-batches/:id/undo` to reverse the whole batch. Single-event undo refuses batched events. Empty actions also keep their request id, so a replay cannot accidentally complete tasks that became due later. **Repot with new pot and soil** opens a form using the same pot validation as plant settings, records a repot completion and updates the pot atomically. Undo restores the previous pot data too. Undo the newest repot first when there have been several.

New HTTP commands are `POST /api/plants/:id/tasks/:taskType/snooze`, `POST /api/plants/:id/tasks/:taskType/skip` and `POST /api/plants/:id/tasks/complete-all`, each with a UUID `request_id`. Single-task commands return an event and task; complete-all returns events and the recomputed schedule. `POST /api/plants/:id/repot` takes `request_id`, `pot_type`, `pot_size_cm` and `soil_type`. Retry with the same UUID and payload; reuse for a different action returns 409. Replaying an undone action does not redo it. `GET /api/tasks?view=upcoming` returns day sections as well as task-type groups. `GET/PUT /api/tasks/settings` reads or saves `{"sort":"task","filter":"all","show_completed":false}`. Every endpoint requires a household session and writes refuse cross-origin requests.

## Photos and journal

On a plant page, add a photo update with a note, optional health and a taken date. Take photo opens the phone camera; Choose photos selects one existing image. Both replace the same selected photo. Choose **Use as cover** on an update, or open **View all updates** to browse that plant's photos. **Journal** on Home shows all plants' updates by taken date then creation time, newest first. Plant settings also allow setting or clearing health. The health precedence rule and equal-date behavior are in CONTEXT.md.

Uploads are limited to 15 MB. Sharp decodes the image, applies EXIF orientation, removes metadata and re-encodes a JPEG original plus a thumbnail up to 400 px. Invalid images, animations, documents and future dates are refused before storage. Dates default to today in HOUSEHOLD_TIMEZONE. The private MinIO bucket stores server-generated `photos/<household>/<plant>/<uuid>-original.jpg` and `-thumb.jpg` keys; Postgres stores only keys and metadata. App routes authenticate and check the household before streaming an image. Do not enable anonymous bucket access. Deleting an update removes both objects before deleting its row; storage failures keep the row so deletion can be retried. Failed uploads roll back the row and remove both object keys.

Configure MINIO_ENDPOINT, MINIO_BUCKET, MINIO_ACCESS_KEY and MINIO_SECRET_KEY at runtime. The endpoint includes http:// or https:// and the port; HTTPS is recommended for deployment. Compose creates its private test bucket. BODY_SIZE_LIMIT is 41M in the runtime image to allow multipart overhead; the app enforces the lower 15 MB file limit even for chunked requests. Runtime dependencies install on Alpine so sharp uses musl-compatible binaries.

Endpoints: `GET/POST /api/plants/:id/photos`, `GET /api/journal`, `PUT /api/photos/:id/cover`, `DELETE /api/photos/:id`, and `GET /api/photos/:id/original|thumb`. POST takes multipart fields photo, note, health and taken_on. Health is optional and uses poor, fair, good or very_good. Plant POST/PUT also accepts optional health. Invalid photo fields return 422, oversized files return 413, and missing or foreign records return 404. All endpoints require a household session and writes refuse cross-origin requests.

The photo API tests start real MinIO through Testcontainers using tests/stack/Minio.Dockerfile, alongside real Postgres. Synthetic JPEGs carry orientation and EXIF GPS, verified before upload. Tests download both stored objects through S3 to check metadata removal and use a fault-injection HTTP proxy to refuse a thumbnail write while the original goes to real MinIO. No personal photos or provider payloads are fixtures.

## Photo identification

On **Add plant**, choose **Identify from photos** and use **Take photo** or **Choose photos** to add one to five photos of the same plant to a shared list. Remove any photo before identifying if needed. The browser applies camera orientation and shrinks each photo to a long edge of 2048 px at JPEG quality 0.85 before upload. If the browser cannot decode it, it sends the original for server decoding. The ranked list shows Pl@ntNet scientific and common names with percentage scores. GBIF-mapped candidates also show the accepted GBIF name, match type and name-match confidence separately. Every pick opens **Confirm species** before the existing plant form. Identification never creates a plant automatically and does not keep the images. Use the separate photo-update form after saving if you want to keep a photo.

`POST /api/identification` accepts multipart `images` fields. The shared Sharp path applies orientation, decodes and shrinks each identification image to a long edge of 1600 px without enlargement, then re-encodes it without metadata before sending JPEGs to Pl@ntNet with an `auto` organ for each. Photo-update originals still keep their full resolution. The adapter has a 20-second timeout covering both upload and identification; the UI shows **Identifying…** while waiting. Quota exhaustion returns 429 with a message; rejected photos return 422 and provider outages return 503. Uploads over the size limit return 413. Each image is bounded to 15 MB and the combined image payload to 40 MB, with 64 KB allowed for multipart overhead, including chunked uploads. These limits, image dimensions and timeout are named in `src/lib/identification.ts`; the runtime body limit is 41M.

Results reuse the household-scoped five-minute species search record. `POST /api/plants` requires its `search_id`, a listed `gbif_key`, and `confirmed: true` for every identification candidate; client scores are refused. A missing Pl@ntNet GBIF ID is mapped through the existing GBIF matcher or dropped. Canonical names come from Pl@ntNet scientificNameWithoutAuthor rather than splitting the name. Known GBIF ranks come from stored taxonomy or species/{key}; unavailable ranks are recorded as UNKNOWN, not assumed to be species. Identification never overwrites an existing species row. Confirmed picks use the shared species-save path, which queues care and name fetches and indexes the stored canonical name as `la`/`gbif`. `GET /api/identification` returns the last quota only when it is at most 50. Quota is stored once per instance in Postgres and survives restart. All identification endpoints require a household session, and writes reject cross-origin requests.

`PLANTNET_BASE_URL` defaults to `https://my-api.plantnet.org/v2/`; `PLANTNET_API_KEY` is private runtime configuration. An empty key reports that identification is not configured without calling upstream. The adapter calls `identify/all` with related images disabled and never logs provider errors that could contain the key. Normal Compose and API tests use synthetic local fakes. Real APIs remain opt-in through `scripts/stack-real-apis`. Tests inspect the multipart images received by the API fake, including a generated GPS-bearing source, and the phone scenario uploads two generated images, including a 4000×3000 image, checks browser downscaling, and confirms both direct and GBIF-mapped picks against synthetic WireMock mappings.
## Species care catalogue

The plant page links to `/species/:gbifKey`. Its self-contained Species care card shows every field, source and fetch time, including unknown values. Owner and family members can set typed overrides or remove them. Numeric watering ranges use their midpoint in days; week and hour benchmarks convert to days, and month benchmarks use 30 days. Multiple sunlight preferences use the least intense recognised tier. Hardiness stays a zone range, not a Celsius conversion.

`GET /api/species/:gbifKey/care` returns identity, effective profile and fetch status. `PUT /api/species/:gbifKey/care/overrides/:field` takes `{ "value": ... }`, validated for that field. DELETE removes it. POST to `/api/species/:gbifKey/care/refresh` queues a refresh without discarding current data or overrides. These require membership and a plant of that species in the same household; sitters cannot edit. Fetches retain only mapped care fields in Postgres, not raw payloads. Never export source data to the repository. See `docs/adr/0001-care-data-sources.md` for measurement and licence limits.

`PERENUAL_BASE_URL` is the server root, default `https://perenual.com/`. `PERENUAL_API_KEY` enables fetching. Without a key the source is disabled, the card says "Perenual not configured", and the card does not poll for it. The Postgres queue claims leased jobs with SKIP LOCKED and recovers expired claims after restart. `PERENUAL_DAILY_CAP` defaults to 100 requests per UTC day, `PERENUAL_PER_MINUTE` to 10 in a rolling minute, and `PERENUAL_MAX_ATTEMPTS` to 5. Search and detail each consume one request. Quota waits preserve attempts and discovered source IDs. 429 sets source-wide backoff, at least 60 seconds and respecting Retry-After. IDs above 3000 are not available on the Personal tier. 401/403 pauses the whole source without discarding jobs or consuming retry attempts. The worker logs that pause once without credentials. Correct the key, restart the app to load it, then request a refresh to resume the source. Transient errors retain a job until the attempt cap. Existing good cache data remains during failures. Completed data refreshes after 30 days, checked at most hourly.

The source list in `src/lib/server/care/registry.ts` owns adapter instances, request limits and priorities. A higher priority wins each available field; owner overrides still win over all sources. Open Plantbook has its own adapter and a registry entry below Perenual, without engine changes. Rule defaults live in `rules.ts`; the card displays them, while absent source fields let the engine apply its own fallback explanations. Task lists batch cache and override reads across species.

The worker polls every two seconds. `PERENUAL_WORKER_ENABLED=false` disables automatic polling for deterministic HTTP tests. `/__test/care-worker` processes one job only when the test clock is enabled in NODE_ENV=test, requires a session and rejects wrong-origin requests. Browser tests use the real loop with synthetic WireMock mappings in `perenual-care.json`.

## Open Plantbook care

`OPEN_PLANTBOOK_API_KEY` enables the source and is sent only in `Authorization: Token <key>`. `OPEN_PLANTBOOK_BASE_URL` is the API root, default `https://open.plantbook.io/api/v1/`, with or without a trailing slash. Compose uses its synthetic WireMock service. Missing credentials disable fetching. `OPEN_PLANTBOOK_DAILY_CAP`, `OPEN_PLANTBOOK_PER_MINUTE` and `OPEN_PLANTBOOK_MAX_ATTEMPTS` default to 100, 10 and 5. These are conservative local caps, not published provider quotas. The existing care worker and quota mechanism handle 401/403 pauses, 429 backoff, retries and thirty-day refreshes.

Search uses `plant/search?alias=<canonical name>&limit=200`. Each result's `display_pid` goes through the existing GBIF client. Only a single EXACT, species-rank, confidence ≥90 candidate with the same accepted GBIF key and no alternatives confirms a pid. Multiple confirmed pids, incomplete search pagination and uncertain candidates are ambiguous. No confirmed pid is no_match. Detail uses `plant/detail/{pid}/?include=care`. The species card shows match status. Owners find unmatched species through Household settings or `/species/unmatched`. `POST /api/species/:gbifKey/care/retry-match` clears only Plantbook's pid, match and source cache and queues a new match; overrides survive. It refuses a running fetch, missing credentials, non-owner callers and foreign-household species. `GET /api/species/unmatched` is the household-scoped owner list.

Versioned mappings in `src/lib/care/open-plantbook.ts`:

- `open_plantbook.humidity@1`: arithmetic midpoint of min/max_env_humid, in 0–100%. Low below 40%, medium from 40% to below 60%, high at 60% or above. The effective humidity need drives the existing indoor misting rule and appears in Based on.
- `open_plantbook.light@1`: arithmetic midpoint of min/max_light_lux, in 0–100000 lux. Below 2000 is full_shade; 2000 to below 5000 is part_shade; 5000 to below 20000 is part_sun; 20000 or above is full_sun. These are app estimates, not a provider classification.
- `open_plantbook.temperature@1`: min/max_temp stay in °C, within −60 to 60, minimum not exceeding maximum.
- `open_plantbook.survival@1`: min_temp_survival stays in °C as `hardinessC`, within −60 to 60. Perenual's `hardiness` remains a separate zone range; there is no zone conversion.
- `open_plantbook.soil_moisture@1`: min/max_soil_moist stay in 0–100% as a sensor range. They are not a top-layer dryness rule and do not determine watering intervals.
- `open_plantbook.care_text@1`: retain plain care text or recognised string entries for soil, sunlight, watering, fertilization and pruning, at most 4000 characters. The care extension's schema is undocumented; unknown shapes fall back rather than becoming schedules. Text is displayed as text, never HTML.

Unknown, non-numeric, non-finite, reversed or out-of-range values are omitted field by field. Every retained field has source `open_plantbook` and a fetched-at time. Priority is override > Perenual > Open Plantbook > rules. Only mapped fields and matching metadata stay in the household database. Open Plantbook's data licence remains unstated; no real payloads or catalogue exports belong in the repo. All fixtures are synthetic.

## Runtime

Runtime config: `GBIF_BASE_URL` defaults to `https://api.gbif.org/v1/`; tests override it with an in-process fake or the Compose alias `gbif`. Other config: `HOUSEHOLD_TIMEZONE`, `DATABASE_URL`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `OIDC_REDIRECT_URL` and `ORIGIN`. Register `/auth/callback` at the provider and set `ORIGIN` to the public app URL. Inject secrets at runtime. Use HTTPS for secure session cookies and PWA installation. `OIDC_ALLOW_INSECURE_HTTP=true` is for the test provider only, never deployment.

The first login becomes the household owner. Other subjects are refused until invitations are implemented. Sessions expire after seven days. `/healthz` checks Postgres; `/metrics` exposes Prometheus metrics. Neither operational endpoint requires login; restrict them at the ingress.

Dbmate applies the timestamped SQL migrations in `migrations/` before the HTTP listener starts. Set `sslmode` in `DATABASE_URL`, such as `require` for deployment or `disable` for a disposable local database. Regenerate the PNG icons with `./scripts/container icons` after changing `assets/icon.svg`.

## Plant lifecycle

Plant settings offer **Move to graveyard**, **Change species** and **Delete plant**. The plant list links to **Graveyard**, where each card shows its move date and cover. Graveyard plant pages show the date, retained history and photos, and **Restore plant** with a site chooser instead of a schedule. Graveyard plants have no tasks and are excluded from active plant and site counts.

Sites with only graveyard plants can be deleted. Deletion clears those plants' site in the same database transaction; active plants still block it. Restoring a detached plant requires a household site. Active plants cannot have a null site.

Lifecycle endpoints require a session and refuse cross-origin writes:

- `GET /api/plants?status=graveyard` lists graveyard plants. The default is active; `site_id` and `count=true` work for either status.
- `POST /api/plants/:id/graveyard` records the first move date. Repeating it keeps the date.
- `POST /api/plants/:id/restore` takes `{}` to keep its existing site, or `{"site_id":"<household site UUID>"}` to choose another. Detached plants require the latter.
- `POST /api/plants/:id/species` takes `search_id`, `gbif_key` and `confirmed` from the same search and confirmation flow used by add-plant. Previous and next species appear in care history and `GET /api/plants/:id/species-changes`. Care events, photos and custom intervals remain unchanged.
- `DELETE /api/plants/:id` takes `{"confirm_name":"<current display name>"}` and returns 204. The UI requires typing that name before enabling the permanent action.

Full deletion commits all row deletion first, then deletes both MinIO objects for each photo. If MinIO refuses cleanup, the plant stays deleted and the server logs every object key to remove later, without storage credentials. An operator can remove those keys from the configured private bucket after fixing storage access. This differs from deleting an individual photo, which retains its row on storage failure for retry.

Schedule reads use the catalogue's `effectiveCareProfiles`, including source caches and household overrides. The API test sets different care overrides for two species through the catalogue API, then verifies changed due dates with unchanged history and plant intervals.

`tests/api/lifecycle.test.ts` covers graveyard exclusions, retained photos and care, restore, mixed active/graveyard site deletion, name confirmation, real MinIO cleanup and a forced delete failure, species confirmation and recomputation, and household/session/origin refusals. `tests/browser/lifecycle.spec.ts` exercises graveyard, restore, species correction and permanent deletion at the phone viewport.

## Weather, rain and outdoor months

The owner sets latitude, longitude and an optional place label in Household settings. Only the owner can change the location. Coordinates are private household data and are never logged. Open-Meteo receives them to provide local forecast and climate. Removing or changing coordinates discards the old cache and resolves old weather events; editing just the label keeps the cache.

`OPEN_METEO_BASE_URL` defaults to `https://api.open-meteo.com/`, and `OPEN_METEO_CLIMATE_BASE_URL` to `https://climate-api.open-meteo.com/`. The client requests `/v1/forecast` with past_days=2, forecast_days=7, the household time zone, and daily temperature_2m_min, temperature_2m_max, precipitation_sum and daylight_duration. Seven forecast days include today. `/v1/climate` requests CMCC_CM2_VHR4 daily temperature_2m_min for 1991 to 2020 and averages them by calendar month. These are modelled climate estimates, not measured frost limits. Both requests have an eight-second timeout. Raw responses and returned coordinates are not retained.

The five-second background loop refreshes Postgres caches at most hourly for forecast and once per calendar month after a successful climate fetch. Failed climate fetches retry after six hours. Event reconciliation runs after a stored forecast, a household date change, or changes to plants and effective care profiles, not on unchanged ticks. Reads never fetch weather. Failed refreshes retain the last good values and unavailable_since; success clears that timestamp. `WEATHER_WORKER_ENABLED=false` disables automatic polling for deterministic tests. `/__test/weather-worker` runs one tick with the test clock enabled, requires membership and refuses foreign origins. The Compose aliases open-meteo and open-meteo-climate point at separate synthetic WireMock mappings.

Rules in `src/lib/schedule/rules.ts` apply only to outdoor watering, before rounding, the 1–60 day clamp and custom intervals:

| Rule | Input and adjustment |
| --- | --- |
| water.weather_temperature@1 | Today's and next six days' mean maximum: ≥28 °C ×0.75; ≤12 °C ×1.25; otherwise ×1 |
| water.weather_daylight@1 | Today's daylight ≤9 hours ×1.25; otherwise ×1 |
| hardiness.default@1 | Unknown in-ground hardiness defaults to 5 °C |
| hardiness.pot_offset@1 | Every pot is 5 °C less hardy than in-ground, identified by pot_type=in_ground |
| hardiness.outdoor_months@1 | Monthly mean daily minimum ≥ applicable hardiness +2 °C |
| weather.rain@1 | Precipitation today or yesterday ≥5 mm |

Alerts and outdoor months first use effective `hardinessC`, a minimum survival temperature already in °C from Open Plantbook or an owner override. Zero is a present value. Without it, weather converts the effective minimum hardiness zone to °C using hardiness.usda_uncertain@1. The catalogue keeps those zones separate from Celsius. This interpretation is uncertain, research R5 and ADR 0001, and the card and alerts say so. With neither field, hardiness.default@1 supplies 5 °C in ground. The species card and plant Based on show the selected source and Celsius value; pots then add the offset. Whole-zone lower edges, rounded to one decimal, are:

| USDA zone | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Minimum °C | -51.1 | -45.6 | -40 | -34.4 | -28.9 | -23.3 | -17.8 | -12.2 | -6.7 | -1.1 | 4.4 | 10 | 15.6 |

Home and plant pages show forecast cold spells below the active outdoor plant's applicable hardiness. `weather_events` retains low_temperature and rain events with start/end dates, payload and resolved_at, ready for the feed and notifications. The weather migration requires `btree_gist`; its migration role must be allowed to create that extension. A partial unique index prevents duplicate open rain events per household/date. An exclusion constraint prevents overlapping open cold spells per plant. Indoor and graveyard plants do not raise cold alerts. Pot and ground outdoor months appear inside the Species care card, with thresholds, rules and climate source. No location shows a link to set it; climate awaiting its first refresh says pending.

After rain, Home offers **Mark N outdoor plants watered**. This uses the same `care_actions` batch mechanism as Handle all tasks, with a water care event for each active outdoor plant. `weather_events.batch_id` references `care_actions.request_id`. There are no separate rain batch tables. Another tap, even with a new UUID, returns that batch without watering again. Each tap UUID is retained in `care_actions`; taps that reuse a batch link through `care_actions.batch_id`, so replay after undo still returns the undone batch. The button stays hidden after a reload; undo makes it available again. **Undo rain watering** undoes the batch, also after a reload. Retry a failed request with the same UUID. A reused UUID belonging to another member or household is refused. A replay after undo returns the original undone batch, not new completions. Individual care-event undo refuses batched events so a rain action cannot be partly undone.

Endpoints, all scoped to the session household:

- `GET/PUT /api/household/location`, PUT with `{"location":{"latitude":0.5,"longitude":0.5,"label":"Test location"}}`, or location=null to clear. Label is optional, up to 200 characters.
- `GET /api/weather` for cache state, today's snapshot, open cold alerts, rain report and latest undoable rain batch.
- `GET /api/species/:gbifKey/outdoor-months`, requiring a household plant of that species.
- `POST /api/weather/rain/water` with `{"request_id":"<client UUID>"}`.
- `POST /api/task-batches/:id/undo`.

Malformed JSON returns 400, invalid fields 422, foreign records 404 and action conflicts 409. Every write checks Origin. Forecast and climate HTTP tests use in-process synthetic Open-Meteo daily arrays. The browser scenario covers setting location, a cold alert, rain watering, one undo and outdoor months at the Pixel 7 viewport.

Public endpoint documentation: https://open-meteo.com/en/docs and https://open-meteo.com/en/docs/climate-api. Outdoor months are starting estimates, not permission to leave a frost-sensitive plant outdoors.
