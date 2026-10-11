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

API tests run real migrations and a synthetic OIDC provider, including PKCE and signed ID tokens. There are no unit tests until the pure schedule engine exists. Browser tests and the local Compose stack come in #3.

Runtime config: `DATABASE_URL`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `OIDC_REDIRECT_URL` and `ORIGIN`. Register `/auth/callback` at the provider and set `ORIGIN` to the public app URL. Inject secrets at runtime. Use HTTPS for secure session cookies and PWA installation. `OIDC_ALLOW_INSECURE_HTTP=true` is for the test provider only, never deployment.

The first login becomes the household owner. Other subjects are refused until invitations are implemented. Sessions expire after seven days. `/healthz` checks Postgres; `/metrics` exposes Prometheus metrics. Neither operational endpoint requires login; restrict them at the ingress.

Dbmate applies the timestamped SQL migrations in `migrations/` before the HTTP listener starts. Set `sslmode` in `DATABASE_URL`, such as `require` for deployment or `disable` for a disposable local database. Regenerate the PNG icons with `./scripts/container icons` after changing `assets/icon.svg`.
