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

Optional real APIs: `./scripts/stack-real-apis` uses the owner's existing Vaultwarden runner, requiring Python 3 on the host. It injects only Pl@ntNet and Perenual keys into the app environment and changes those two base URLs. It never saves keys. Do not use this for normal tests; Perenual's quota is 100 requests per day.

## Sites

After login, open **Sites** from the home screen to create, edit or delete a site. The list counts its plants. Deletion requires confirmation and refuses sites that still have plants.

The UI and API tests use the same JSON endpoints: `GET /api/sites`, `POST /api/sites`, and `GET`, `PUT`, `DELETE /api/sites/:id`. POST and PUT take all site conditions using the vocabulary in `CONTEXT.md`: `name`, `location`, `light`, `grow_light`, `humidity`, `draft`, `warm_min_c`, `warm_max_c`, `cold_min_c`, `cold_max_c`. Temperature bounds may be omitted or null. PUT replaces conditions rather than patching them. Invalid fields return 422 with an `errors` object keyed by field; malformed JSON returns 400. Deletion returns 204 or 409 with a message saying how many plants to move or remove. Every endpoint and page requires a household session.

## Plants

Open **Plants** from home, then **Add plant**. Search by name, choose a candidate and confirm uncertain matches before filling in its site, optional nickname, cultivar and birthday, pot and plant attributes. The plant page links to its settings and to the site's plant list. Site settings are now at /sites/:id/edit. Plant lists filter by display name or scientific name and show the chosen cover photo, or a placeholder.

API endpoints: `GET /api/species`, `GET /api/species/search?q=...`, `GET /api/plants` with optional `site_id` or `count=true` for a count only, `POST /api/plants`, and `GET` or `PUT /api/plants/:id`. Search returns `search_id` and candidates. POST carries `search_id`, the chosen `gbif_key`, `confirmed: true` when required, and the plant attributes. PUT replaces the attributes without changing the species. Every page and endpoint requires a household session.

Search checks stored scientific and canonical names first. Only a full name match, ignoring case and surrounding spaces, can skip confirmation locally. Partial local hits require confirmation and carry no match type or confidence. Without a full local match, the GBIF adapter calls species/match with kingdom=Plantae and verbose=true. GBIF candidates appear alongside local partial hits, which remain available during an outage. The multilingual name index is ticket #11. Species use the accepted GBIF key for synonyms. If the match omits an accepted name, the adapter fetches species/{acceptedUsageKey}. Both requests share a three-second timeout. GBIF names are attributed under CC BY 4.0.

The server retains search results for five minutes, with a maximum of 1,000 records per process, scoped to the household. After expiry or restart, search again. EXACT species-rank matches with confidence at least 90 need no confirmation. Other match types, lower confidence, lower taxon ranks and alternatives require confirmed=true for that exact chosen key. NONE and taxa outside Plantae or above species rank are refused. Client-sent taxonomy is ignored.

All plant fields use the vocabulary in CONTEXT.md. POST and PUT validate on the server; field errors return 422, malformed JSON returns 400, and GBIF outages return 503. Birthday uses the server clock's UTC date. The database checks birthday against the recorded validation date so the test clock and database do not disagree. Enum, positive-integer and household-site constraints also live in Postgres. Occupied-site tests now create plants through the API, and the phone scenario adds one through the UI.

## Care tasks

Home shows **Today** when plants exist, grouped by task type with late badges. Tap **Done** once to record any task and see the next date; the same row offers **Undo**. Water, Fertilize, Mist, Clean leaves, Remove dead flowers and leaves, Seasonal cut-back and Repot share the same completion path. Plant pages show the next due date for each task, a **Based on** panel with input values, factors and sources, and care history with the member's OIDC name or email, falling back to "Member". Undone events remain marked in history.

The pure engine in `src/lib/schedule/engine.ts` takes a care profile with sources, site, pot, plant, per-task history, custom intervals, today, added date and optional weather. Weather values are recorded but not yet used for adjustments. The single `taskTypes` list and per-task rules in `src/lib/schedule/rules.ts` define labels, defaults, factors, applicability, anchoring and seasons. Watering starts at seven days, applies site, pot, plant and anchor-month factors, rounds to whole days and clamps to 1–60 days before custom intervals. The northern-hemisphere month table stretches winter watering. Fertilizing defaults to twice the current watering interval, with longer defaults for sticks and slow-release, and shifts out-of-season dates into March–September. Pruning defaults to March. Full defaults and terminology are in `CONTEXT.md`. These are starting estimates, not a substitute for checking soil moisture.

Dates use `HOUSEHOLD_TIMEZONE`, default `Europe/Oslo`. Server reads use the shared clock and Intl to resolve instants to calendar dates before calling the engine. Watering with no effective completion is due on the plant's added date. Other interval tasks start at added date plus interval. Otherwise, the last non-undone completion plus the current interval determines the next date, subject to seasonal and pruning rules. No due date is stored.

API endpoints: `GET /api/tasks?view=today` returns dated task groups, `GET /api/plants/:id/tasks` returns the plant's schedule, and `GET /api/plants/:id/history` returns all care events, including marked undone entries. `POST /api/plants/:id/tasks/:taskType/complete` takes `{"request_id":"<client UUID>"}`. Retry the same action with the same UUID. A reused UUID for another action returns 409. `POST /api/care-events/:id/undo` marks that event undone; a second undo returns 409. Both commands return the event and recomputed task. Every endpoint requires a household session; foreign plants and events return 404. Cross-origin writes are refused.

Plant settings allow a custom interval per task type, set or cleared, and a misting switch. Custom intervals are whole days from 1–365 and override only the interval, not applicability or the fertilizing season. `GET` and `PUT /api/plants/:id/care-settings` use `{"misting_enabled":true,"customIntervals":{"clean_leaves":30}}`. PUT replaces the interval map; omit a task key to clear its override. Unknown types, fractional or out-of-range intervals and non-boolean switches return 422; malformed JSON returns 400.

**Household settings** on Home controls misting for all plants. `GET` and `PUT /api/household/settings` use `{"misting_enabled":true}`. Household and plant switches both default on; both must be on for indoor misting to apply. These endpoints require a session, reject cross-origin writes and scope settings to the session's household. All members may edit until roles are enforced in #16.

## Photos and journal

On a plant page, add a photo update with a note, optional health and a taken date. The file input supports the phone camera. Choose **Use as cover** on an update, or open **View all updates** to browse that plant's photos. **Journal** on Home shows all plants' updates by taken date then creation time, newest first. Plant settings also allow setting or clearing health. The health precedence rule and equal-date behavior are in CONTEXT.md.

Uploads are limited to 15 MB. Sharp decodes the image, applies EXIF orientation, removes metadata and re-encodes a JPEG original plus a thumbnail up to 400 px. Invalid images, animations, documents and future dates are refused before storage. Dates default to today in HOUSEHOLD_TIMEZONE. The private MinIO bucket stores server-generated `photos/<household>/<plant>/<uuid>-original.jpg` and `-thumb.jpg` keys; Postgres stores only keys and metadata. App routes authenticate and check the household before streaming an image. Do not enable anonymous bucket access. Deleting an update removes both objects before deleting its row; storage failures keep the row so deletion can be retried. Failed uploads roll back the row and remove both object keys.

Configure MINIO_ENDPOINT, MINIO_BUCKET, MINIO_ACCESS_KEY and MINIO_SECRET_KEY at runtime. The endpoint includes http:// or https:// and the port; HTTPS is recommended for deployment. Compose creates its private test bucket. BODY_SIZE_LIMIT is 16M in the runtime image to allow multipart overhead; the app enforces the lower 15 MB file limit even for chunked requests. Runtime dependencies install on Alpine so sharp uses musl-compatible binaries.

Endpoints: `GET/POST /api/plants/:id/photos`, `GET /api/journal`, `PUT /api/photos/:id/cover`, `DELETE /api/photos/:id`, and `GET /api/photos/:id/original|thumb`. POST takes multipart fields photo, note, health and taken_on. Health is optional and uses poor, fair, good or very_good. Plant POST/PUT also accepts optional health. Invalid photo fields return 422, oversized files return 413, and missing or foreign records return 404. All endpoints require a household session and writes refuse cross-origin requests.

The photo API tests start real MinIO through Testcontainers using tests/stack/Minio.Dockerfile, alongside real Postgres. Synthetic JPEGs carry orientation and EXIF GPS, verified before upload. Tests download both stored objects through S3 to check metadata removal and use a fault-injection HTTP proxy to refuse a thumbnail write while the original goes to real MinIO. No personal photos or provider payloads are fixtures.

## Runtime

Runtime config: `GBIF_BASE_URL` defaults to `https://api.gbif.org/v1/`; tests override it with an in-process fake or the Compose alias `gbif`. Other config: `HOUSEHOLD_TIMEZONE`, `DATABASE_URL`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `OIDC_REDIRECT_URL` and `ORIGIN`. Register `/auth/callback` at the provider and set `ORIGIN` to the public app URL. Inject secrets at runtime. Use HTTPS for secure session cookies and PWA installation. `OIDC_ALLOW_INSECURE_HTTP=true` is for the test provider only, never deployment.

The first login becomes the household owner. Other subjects are refused until invitations are implemented. Sessions expire after seven days. `/healthz` checks Postgres; `/metrics` exposes Prometheus metrics. Neither operational endpoint requires login; restrict them at the ingress.

Dbmate applies the timestamped SQL migrations in `migrations/` before the HTTP listener starts. Set `sslmode` in `DATABASE_URL`, such as `require` for deployment or `disable` for a disposable local database. Regenerate the PNG icons with `./scripts/container icons` after changing `assets/icon.svg`.
