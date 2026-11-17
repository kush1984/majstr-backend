# Contributing — the full rules (moved out of CLAUDE.md on 2026-11-17)

CLAUDE.md keeps only the hard rules; this is the unabridged text it was cut from.


SaaS backend for Ukrainian contractors: auth, projects, clients, catalog, estimates (PDF + public
client portal with online signing), subscriptions/admin, email verification, web push, monitoring.
[README.md](README.md) has end-user setup and the public REST contract; this file is for contributors.

**This file is deliberately short (it loads every session) — keep it that way.** Every bullet of the
Architecture index has an unabridged twin in
**[docs/architecture-index-detail.md](docs/architecture-index-detail.md)**, paired by title: put the
reasoning THERE and the invariant here. Per-area narrative lives in
**[docs/architecture.md](docs/architecture.md)** — read the relevant section before working in that
area. History: `docs/iteration-*.md`; deferred decisions: [docs/open-questions.md](docs/open-questions.md).
Pre-compression index (names only old commits use):
[docs/architecture-index-2026-10-04.md](docs/architecture-index-2026-10-04.md).

## Stack (pinned)

- **Spring Boot 4.0.6** (Spring Framework 7, Jakarta EE 11) on **Java 21 (LTS)**
- **Gradle Kotlin DSL** — toolchain pinned to JDK 21 in `build.gradle.kts`
- **PostgreSQL 17** via `docker-compose.yml`, schema owned by **Flyway**
- **Spring Security 7**, stateless, JWT via **jjwt 0.12.x** (HS256)
- **Bucket4j 8.x** — login rate limiting
- **Jackson 3** — package is `tools.jackson.*`, not `com.fasterxml.jackson.*` (see index → Gotchas)
- **Lombok**, **springdoc-openapi 2.8.x**, **JUnit 5 + MockMvc**

Don't bump these without a clear reason — they fit together. (Java 21 over 25: Java 25 had no reliable build image on Railway.)

## Common commands

```bash
docker compose up -d   # Postgres (needs .env with POSTGRES_PASSWORD)
./gradlew bootRun      # run the app (env vars from .env must be exported)
./gradlew build        # full build with verification (./gradlew test = tests only)
```

JWT secret and DB credentials come from **env vars only** — never hardcode; the base
`application.yml` references `${JWT_SECRET}` with no default and startup fails fast without it.

## Package layout

```
com.majstr.backend
├── config/ controller/ repository/ entity/ dto/   — the usual; controllers are thin
├── service/                   — business logic, @Transactional boundaries
│   ├── ai/                    — LLM provider plumbing (per-flow provider/model)
│   ├── album/                 — full-album takeoff: surfaces + electrical (built, NOT exposed)
│   ├── importer/              — estimate / receipt / catalog import from file or photo
│   ├── fiscal/                — fiscal-QR decode + ДПС lookup
│   └── measurement/           — measurement domain + project-document import
├── security/                  — JwtService, filters, UserPrincipal, body-cache wrapper
├── feature/                   — plan gates: Feature/Limit enums, PlanConfig, guards
├── billing/  storage/         — monobank checkout + auto-renew / local + S3-R2
├── email/  push/              — Resend HTTP client + templates / Web Push (VAPID)
├── bootstrap/  dev/           — startup seeding / @Profile("dev") only
└── exception/                 — typed exceptions + GlobalExceptionHandler
```

Layering rule: `controller → service → repository`. Entities never leave the service layer —
controllers return DTOs. `passwordHash` never appears in any response (`UserResponse` excludes it;
`User#toString` excludes it).

## Schema (Flyway)

`hibernate.ddl-auto: validate` — never express schema changes in entity annotations. Add a new
`V<N>__<desc>.sql` under `src/main/resources/db/migration/`; **check the highest number first**
(latest is **V215**; V183, V185 skipped). **Never edit an applied migration** — Flyway checksums it and a changed file
fails startup (a COMMENT fix goes in the next migration's header, B-53). **A self-check may `RAISE EXCEPTION` only about the migration's OWN
work** (review B-50): an assertion about data an admin can rename between deploys is a `RAISE
WARNING`, and the integration tests keep it honest.
**The same goes for PRECONDITIONS about the library** (V176 took prod down refusing on one
admin-edited price): a row no longer where the ruling found it is a `WARNING` + skip. New `Trade` enum constant → migration to extend the `user_trades` CHECK (a
master-invented trade instead goes in `user_trade`, no migration needed).

## Testing

Spring Boot 4 removed all test-slice annotations (`@WebMvcTest`, `@DataJpaTest`, …). Two patterns:

1. **Controller tests** — pure Mockito (`@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`
   + `MockMvcBuilders.standaloneSetup(controller)`, register `GlobalExceptionHandler` via
   `.setControllerAdvice(...)`). No Spring context, no DB.
2. **Integration tests** — full `@SpringBootTest` against a **Testcontainers** Postgres via
   `IntegrationTestBase` (`@ActiveProfiles("test")`). **Docker is required to build** (they fail, not
   skip). Use for what Mockito can't see (migrations, native/JPQL queries, CHECKs, the
   filter chain). Name `…IntegrationTest`, **not** `…IT`.

`application-test.yml` holds the test JWT secret and the settings that slice loads.

## Conventions

- **Language**: all code, comments, logs, SQL, YAML, Markdown — **English only**; the user chats in
  Ukrainian but artifacts stay English.
- **Comments**: default to none; a one-liner only when the *why* is non-obvious. Don't restate code.
- **A catalog position names the WORK and its RESULT, never a brand** («Каталог описує роботу і
  результат, а не бренд») — no manufacturer, product name or system code (Knauf C112 / W623 / D112)
  in a `catalog_templates` name. A standard may be cited in a `description`; a brand may not name a job.
- **Boundaries of change**: keep changes scoped. Bug fix ≠ refactor. No abstractions for hypothetical
  future needs. **No backwards-compat shims** for code that hasn't shipped — greenfield, just change it.
- **Tests + green build before push (hard gate)**: every change updates/adds tests, and **Claude runs
  `./gradlew build` itself** (~2-3 min; Testcontainers and Docker work). Only push on green — never
  push red, never report green without a run. Red → fix to green first.
- **PWA gate = MIRROR CI's `verify` job, in order** (`majstr-pwa/.github/workflows/ci.yml`):
  `npm run lint` → `npx tsc -b` → `npm run typecheck:tests` → `npx vitest run` → `npx vite build`.
  **`npm run lint`** (`--max-warnings 0`: a stray `!`/`as` fails) and **`npm run typecheck:tests`** (a
  SEPARATE tsconfig for the TEST files, which `tsc -b` skips) each reddened CI alone.
  "build + vitest were green" is NOT the gate.
- **UI: what the row shows, the row edits** — «Що видно в рядку — редагується в рядку. Діалог — для
  решти.» New lists use `InlineNumberCell` / `InlineTextCell`; save on blur / Enter / «Далі» /
  `visibilitychange`, cancel on Esc / «Назад»; empty number = 0, empty name not saved; toast
  «Збережено · Скасувати» 5 s. **Crew prices never reach the client nor the catalog.**
  [docs/iteration-inline-editing.md](docs/iteration-inline-editing.md).
- **Offline/service-worker changes also need `npm run test:e2e:offline`** (CI runs the shell spec).
  The normal e2e runs on the Vite dev server where the SW is DISABLED, so no dev-based test sees an
  SW regression. Verify a new one fails without the fix — check the SOURCE, not minified `dist/sw.js`.

## Not implemented yet (don't claim these exist)

- No multi-instance rate-limit store (in-memory `ConcurrentHashMap`).
- No in-app reply to a client message — the inbox is one-way by design; the master follows up out-of-band.
- No offline photo upload **except act receipts** (PWA-only); the general blob outbox (O6) is open.
- No TEAM multi-user workspaces; TEAM is a plan, not a shared workspace yet.
- Full list of deferred decisions: [docs/open-questions.md](docs/open-questions.md).

**Before claiming a gap, check the Architecture index below** — this list has been wrong before;
what landed when is `git log` and `docs/iteration-*.md`.

## Open-question log

[docs/open-questions.md](docs/open-questions.md) keeps deferred decisions and known gaps. Run the
`open-questions` skill when the user starts a new step/feature, **before writing code**. A closed
item stays, marked `RESOLVED` + a one-line note. Keep `docs/iteration-*.md` updated too.

