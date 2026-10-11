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

API tests run real migrations and a synthetic OIDC provider, including PKCE and signed ID tokens. There are no unit tests until the pure schedule engine exists.

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

The minimal plants table is ready for ticket #5. There is no plant API or UI yet, so occupied-site deletion is covered by API and browser tests with synthetic SQL plant fixtures.

## Runtime

Runtime config: `DATABASE_URL`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `OIDC_REDIRECT_URL` and `ORIGIN`. Register `/auth/callback` at the provider and set `ORIGIN` to the public app URL. Inject secrets at runtime. Use HTTPS for secure session cookies and PWA installation. `OIDC_ALLOW_INSECURE_HTTP=true` is for the test provider only, never deployment.

The first login becomes the household owner. Other subjects are refused until invitations are implemented. Sessions expire after seven days. `/healthz` checks Postgres; `/metrics` exposes Prometheus metrics. Neither operational endpoint requires login; restrict them at the ingress.

Dbmate applies the timestamped SQL migrations in `migrations/` before the HTTP listener starts. Set `sslmode` in `DATABASE_URL`, such as `require` for deployment or `disable` for a disposable local database. Regenerate the PNG icons with `./scripts/container icons` after changing `assets/icon.svg`.
