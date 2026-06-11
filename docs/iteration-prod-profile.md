# Production profile hardening (backend)

- **Status:** ✅ Backend code complete — `./gradlew build` pending a local run
  (Gradle isn't reachable from the agent's sandbox).
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** none.
- **Goal:** make `SPRING_PROFILES_ACTIVE=prod` safe to deploy — no API surface
  leak, trustworthy client IP behind the proxy, locked-down CORS, and no dev
  defaults silently surviving into production. **Backend only.** Everything
  lands in `application-prod.yml`; the base and dev files are untouched.

## What changed (application-prod.yml)

- **Swagger / OpenAPI OFF:** `springdoc.api-docs.enabled=false` +
  `springdoc.swagger-ui.enabled=false`. `/swagger-ui.html` and `/v3/api-docs`
  return 404 in prod. (The `SecurityConfig` whitelist entries stay — they just
  permit a 404 now.) Dev keeps Swagger.
- **Forwarded headers:** `server.forward-headers-strategy=framework`. Spring's
  `ForwardedHeaderFilter` applies the forwarded client IP to `getRemoteAddr()`
  and strips the `X-Forwarded-*` headers. The rate-limit filters
  (`LoginRateLimitFilter`, `RegisterRateLimitFilter`,
  `PublicPortalRateLimitFilter`) read `X-Forwarded-For` first, which is now null,
  so they fall back to the corrected `getRemoteAddr()` — the genuine client IP.
  **No filter code changed.** Safe only because prod is reachable solely through
  Railway's proxy; dev stays on the default `NONE`, so its direct
  `X-Forwarded-For` parsing is unaffected.
- **CORS locked down:** `app.cors.allowed-origins=${APP_CORS_ORIGINS}` — no
  default, so no localhost and no `*`. Production must set the real origin(s)
  (e.g. `https://majstr.pro`); a missing value fails fast. The CORS bean
  (`SecurityConfig`) is unchanged — it reads the same property, which now has a
  prod-only source. (Credentials are allowed, so `*` was never an option anyway.)
- **No dev defaults in prod (fail-fast):** the datasource is overridden to
  `${DB_URL}` / `${DB_USERNAME}` / `${DB_PASSWORD}` with **no defaults**, so prod
  can't silently fall back to the local Postgres / default password from the base
  file. `app.email.app-url=${APP_URL}` and `app.portal.public-base-url=${PORTAL_BASE_URL}`
  are likewise required — a localhost verify-link / portal URL in prod is a real
  bug, not a harmless default. `JWT_SECRET` was already env-required in the base
  file (no default), so it fails fast in every profile.

## Confirmed unchanged

- **Actuator** stays health-only with `show-details: never` — inherited from the
  base file, which applies to prod; the prod file does not loosen it. (Verified
  in the test.)
- **Dev profile** (`application-dev.yml`) and the base `application.yml`
  Swagger/CORS settings are untouched, so local dev keeps Swagger + localhost CORS.
- No Java changed — this is purely profile config. The fail-fast and forwarded-IP
  behaviors come from Spring honoring the prod properties.

## Env vars required in prod (deploy checklist)

```
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET=...                 # already required everywhere
DB_URL=  DB_USERNAME=  DB_PASSWORD=
APP_CORS_ORIGINS=https://majstr.pro
APP_URL=https://app.majstr.pro          # PWA host (verify-email links)
PORTAL_BASE_URL=https://majstr.pro      # portal/share links
```
(Plus the already-documented fail-soft ones when enabling those features:
`RESEND_API_KEY`/`EMAIL_FROM`, `VAPID_*`, `SENTRY_DSN`, `STORAGE_KIND=s3` + `R2_*`.)

## Tests

`ProdProfileConfigTest` (pure YAML load, no context/DB): Swagger + api-docs
disabled; `forward-headers-strategy=framework`; CORS is `${APP_CORS_ORIGINS}`
with no localhost/`*`/default; datasource + APP_URL + PORTAL_BASE_URL are raw
`${VAR}` placeholders (no baked-in default); JWT secret is env-required in base;
actuator stays health-only + `show-details: never`.

## Build-verification note

Gradle can't run in the agent sandbox. Run `./gradlew build` locally — no new
dependency, no migration, config-only. To smoke-test prod locally:
`SPRING_PROFILES_ACTIVE=prod` with the required env vars set → `/swagger-ui.html`
404s, `/actuator/health` works, CORS rejects an unlisted origin; omit `DB_PASSWORD`
→ startup fails fast.
