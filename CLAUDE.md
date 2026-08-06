# Majstr Backend — Claude guide

SaaS backend for Ukrainian contractors: auth, projects, clients, catalog, estimates (PDF + public
client portal with online signing), subscriptions/admin, email verification, web push, monitoring.
[README.md](README.md) has end-user setup and the public REST contract; this file is for contributors.

**This file is deliberately short (it loads every session).** The non-obvious architecture notes and
gotchas live in **[docs/architecture.md](docs/architecture.md)** — the index at the bottom links into
it. Read the relevant section there before working in that area. Per-feature history is in
`docs/iteration-*.md`; deferred decisions in [docs/open-questions.md](docs/open-questions.md).

## Stack (pinned\edited)

- **Spring Boot 4.0.6** (Spring Framework 7, Jakarta EE 11) on **Java 21 (LTS)**
- **Gradle Kotlin DSL** — toolchain pinned to JDK 21 in `build.gradle.kts`
- **PostgreSQL 17** via `docker-compose.yml`, schema owned by **Flyway**
- **Spring Security 7**, stateless, JWT via **jjwt 0.12.x** (HS256)
- **Bucket4j 8.x** — login rate limiting
- **Jackson 3** — package is `tools.jackson.*`, not `com.fasterxml.jackson.*` (see index → Gotchas)
- **Lombok**, **springdoc-openapi 2.8.x**, **JUnit 5 + MockMvc**

Don't bump these without a clear reason — the combo is chosen for Spring Boot 4 / Spring 7 / Jakarta
EE 11 / Java 21 compatibility. (Java 21 over 25: Spring Boot 4's baseline is Java 17, the code uses no
22-25-only feature, and Java 25 had no reliable build image on Railway.)

## Common commands

```bash
docker compose up -d      # Postgres (needs .env with POSTGRES_PASSWORD)
./gradlew bootRun         # run the app (env vars from .env must be exported)
./gradlew test            # tests
./gradlew build           # full build with verification
```

JWT secret and DB credentials come from **env vars only** — never hardcode. The base
`application.yml` references `${JWT_SECRET}` with no default; startup fails fast if it isn't set.

## Package layout

```
com.majstr.backend
├── MajstrApplication.java     — @SpringBootApplication, registers @ConfigurationProperties
├── config/                    — SecurityConfig, OpenApiConfig, *Properties records
├── controller/                — REST endpoints (thin, delegate to services)
├── service/                   — business logic, @Transactional boundaries
│   ├── ai/                    — LLM provider plumbing (per-flow provider/model)
│   ├── album/                 — full-album takeoff: surfaces + electrical (built, NOT exposed)
│   ├── importer/              — estimate / receipt / catalog import from file or photo
│   └── measurement/           — measurement domain + project-document import
├── repository/                — Spring Data JPA interfaces
├── entity/                    — JPA entities (Lombok-annotated)
├── dto/                       — request/response records, validated with jakarta.validation
├── security/                  — JwtService, filters, UserPrincipal, body-cache wrapper
├── feature/                   — plan gates: Feature/Limit enums, PlanConfig, guards
├── billing/                   — monobank checkout, signatures, auto-renew
├── storage/                   — pluggable file storage (local / S3-R2)
├── email/  push/              — Resend HTTP client + templates / Web Push (VAPID)
├── bootstrap/  dev/           — startup seeding / @Profile("dev") only
└── exception/                 — typed exceptions + GlobalExceptionHandler
```

Layering rule: `controller → service → repository`. Entities never leave the service layer —
controllers return DTOs. `passwordHash` never appears in any response (`UserResponse` excludes it;
`User#toString` excludes it).

## Schema (Flyway)

`hibernate.ddl-auto: validate` — never express schema changes in entity annotations. Add a new
`V<N>__<desc>.sql` under `src/main/resources/db/migration/`; **check the highest number first** (`ls`
+ sort numerically — latest is **V89**). **Never edit an applied migration** — Flyway checksums it and
a changed file fails startup. New `Trade` enum constant → migration to extend the `user_trades` CHECK.
(Detail + the "testing a DATA migration" pattern: index → Flyway / catalog.)

## Testing

Spring Boot 4 removed all test-slice annotations (`@WebMvcTest`, `@DataJpaTest`, …). Two patterns:

1. **Controller tests** — pure Mockito (`@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`
   + `MockMvcBuilders.standaloneSetup(controller)`, register `GlobalExceptionHandler` via
   `.setControllerAdvice(...)`). No Spring context, no DB.
2. **Integration tests** — full `@SpringBootTest` against a **Testcontainers** Postgres via
   `IntegrationTestBase` (`@ActiveProfiles("test")`). **Docker is required to build** (they fail, not
   skip). Use for anything Mockito can't see: a migration applying, a `nativeQuery`/`@Query`, a CHECK
   constraint, lazy loading on a detached entity, the security filter chain end to end. Name
   `…IntegrationTest`, **not** `…IT` — discovery is name-based.

`application-test.yml` holds the test JWT secret and the settings that slice loads.

## Conventions

- **Language**: all code, comments, logs, SQL, YAML, Markdown — **English only**. The user chats in
  Ukrainian but artifacts stay English.
- **Comments**: default to none; a one-liner only when the *why* is non-obvious. Don't restate code.
- **Boundaries of change**: keep changes scoped. Bug fix ≠ refactor. No abstractions for hypothetical
  future needs. **No backwards-compat shims** for code that hasn't shipped — greenfield, just change it.
- **Tests + green build before push (hard gate)**: every change updates/adds tests, and the build must
  be green **before any push** — keep `master` green. **Claude cannot run Gradle in its sandbox**
  (loopback socket blocked). So: Claude writes the tests, **the user runs `./gradlew build` locally and
  confirms green**, then Claude pushes. Red → user pastes output, Claude fixes to green first.
- **PWA gate = MIRROR CI's `verify` job, in order** (`majstr-pwa/.github/workflows/ci.yml`):
  `npm run lint` → `npx tsc -b` → `npm run typecheck:tests` → `npx vitest run` → `npx vite build`.
  Two steps are easy to forget and each has reddened CI on its own: **`npm run lint`**
  (`eslint --max-warnings 0` — a stray `!`/`as` is a HARD error) and **`npm run typecheck:tests`**
  (a SEPARATE, stricter tsconfig that type-checks the TEST files — `npm run build`'s `tsc -b` does
  **not**). `tsc --noEmit` checks a looser program and `vite build` doesn't type-check at all —
  neither substitutes. "build + vitest were green" is NOT the gate; lint + test-typecheck are.
- **Offline/service-worker changes also need `npm run test:e2e:offline`** (CI runs the shell spec).
  The normal e2e runs on the Vite dev server where the SW is DISABLED, so no dev-based test sees an
  SW regression. When adding one, verify it fails without the fix — check the SOURCE, not the
  minified `dist/sw.js`.

## Not implemented yet (don't claim these exist)

- No multi-instance rate-limit store (in-memory `ConcurrentHashMap`).
- No in-app reply to a client message — the inbox is one-way by design; the master follows up out-of-band.
- No offline photo upload — every other daily flow authors offline, photos do not (blob outbox backlogged).
- No TEAM multi-user workspaces; TEAM is a plan, not a shared workspace yet.
- Full list of deferred decisions: [docs/open-questions.md](docs/open-questions.md).

**Recently shipped — do NOT describe these as missing** (this list has been wrong before): integration
tests (Testcontainers slice), billing/payments (monobank checkout, auto-renew, yearly tariff, PRO
trial), password reset, client messages with attachments + retention, album takeoff (built, not
exposed), offline-first authoring (everything except photos), estimate duplication with a per-line
markup, bulk line deletion, the 15-day PRO trial with T-3…T-1 reminders, multi-sheet photo recognition
with `sheetKind`.

## Open-question log

[docs/open-questions.md](docs/open-questions.md) keeps deferred decisions and known gaps. There is a
skill at `.claude/skills/open-questions/SKILL.md` ("use at the start of every new iteration") — trigger
it via the Skill tool whenever the user kicks off a new step/feature, **before writing code**. It reads
the file, classifies every `OPEN` item against the upcoming work, and offers to promote/close/add.
Update statuses inline; a closed item stays with `RESOLVED` + a one-line note. The skill also reminds
you to keep the step's own `docs/iteration-*.md` updated.

## Architecture index

One line per non-obvious fact; **full detail in [docs/architecture.md](docs/architecture.md)** — read
the section before working in that area.

- **Auth flow** — register/login/refresh/logout/me; `LoginRateLimitFilter` runs before `JwtAuthenticationFilter`.
- **Refresh tokens** — hashed at rest (SHA-256), rotated (old revoked on use), swept daily; **the PWA must single-flight `/refresh`** or a burst of 401s self-logs-out.
- **Email verification is soft** — `@Async` fail-soft; only `POST /estimates/{id}/share` is gated (403 `EMAIL_NOT_VERIFIED`); email editable only while unverified.
- **Web push** — VAPID, `@Async`, env-gated fail-soft; `deliver()` MUST pass `Encoding.AES128GCM` (legacy `aesgcm` → FCM 403).
- **Client portal is project-level** — `project_share_links` token + `estimates.portal_visible`; legacy per-estimate `?t=` links stay valid.
- **Client messages** — one-way inbox (renamed from "questions", V74); entity field `read` vs view JSON key `isRead`; message-link + file attachments + 6-month retention (V75-77).
- **Login rate limit** — `CachedBodyHttpServletRequest` (re-readable body); key `email|ip`; in-memory (single-node only).
- **Signed estimates are immutable** — mutate/delete → 409 `ESTIMATE_SIGNED`; owner-only `reopen`; `@Version` optimistic lock (V23).
- **Spring Security 7** — lambda DSL only; `PUBLIC_PATHS`; **`RestAuthenticationEntryPoint` keeps 401 vs 403 disjoint**.
- **Localization** — `messages.properties` is **Ukrainian** (base/fallback); exceptions stay English, advice maps type→bundle key (or key passed at throw site); filters use `requestLocale`.
- **Error shape** — `ErrorResponse`; **401 (re-auth) and 403 (not allowed) are disjoint**; client-disconnect → `null`, no Sentry; quiet 404 for scanner probes; do NOT widen the disconnect walk to "any IOException".
- **Offline idempotent creates** — `X-Entity-Uuid` header, idempotency **before** limit checks; wired on clients/projects/estimates/items; new offline entity → follow the pattern.
- **File storage** — pluggable local/S3-R2 via `StorageConfig` (`app.storage.kind`); reads always via `FileController`; keys identical across backends.
- **AI flows (`service/ai/`)** — per-flow provider+model; `AiExtractors.forFlow(...)` resolved at startup + logged; a typo disables ONE flow (503); an empty config value = absent.
- **Estimate import (Excel/photo)** — `import/parse|commit`, `Feature.ESTIMATE_IMPORT`; raw-HTTP extractor; failure → 503 `AI_UNAVAILABLE` (retries transient 429/5xx/529); uploaded file discarded.
- **`sheetKind`** — `PRINTED_PLAN` vs `HAND_DRAWN`; **default leans PRINTED_PLAN** (incl. missing field); passport (the one sheet in metres) rules gated behind ≥2 evidence signals.
- **Duplicate with markup** — `source_unit_price` on the **line** (V85), not one estimate percent; economy counts the difference; bulk delete cascades parent→duplicate only.
- **Consolidated / receipt import / object photos** — `consolidate` (new DRAFT); `RECEIPT_IMPORT` (adds to open estimate, no catalog upsert); photos are **private** (auth-owner + portal-token streams only, never `/api/files/**`).
- **Catalog is reference data, estimate lines are snapshots** — templates copied BY VALUE; `estimate_items` carry their own name/unit/price with **no FK** (the client signed those numbers); a catalog rewrite must notice the master (`catalog_update_notices`); default catalog is works-only (V81).
- **«%» is a share OF something (V88)** — base `POSITION`/`TOTAL` (per-type: works vs materials), `MANUAL` retired; **`line_total` is STORED** (server-authored, never from a request); only a `TOTAL` percent may be negative (+/− toggle, V89).
- **Mirrored formulas — change BOTH sides together** — `EstimateMath.recalculate` ↔ `useEstimate.recomputeLines` (percent pass); `MeasurementCalc` ↔ `measurementCalc.ts`.
- **Estimate line order** — explicit reorder; `EstimatePdfService` mirrors the grouping (change both or the PDF disagrees).
- **One estimate from several templates** — concat + dedup by lowercased name; estimate limit checked once.
- **Album takeoff (`service/album/`)** — **built + tested but NOT exposed**; don't call it available; when wired, run async (minutes-long), never on a request thread.
- **Entities vs records** — entities are Lombok JPA (`@EqualsAndHashCode(of="id")`); DTOs are records; don't mix Lombok with records.
- **Gotchas** — Jackson 3 = `tools.jackson.*`; SB4 splits auto-configs (`spring-boot-flyway`, `spring-boot-testcontainers` — declare explicitly); `TestRestTemplate` gone (use JDK `HttpClient`); Testcontainers 2.x renamed to `testcontainers-*`; JWT ≥ 32 bytes; keep the `-parameters` flag; `open-in-view: false`.
