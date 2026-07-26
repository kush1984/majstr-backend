# Iteration: the integration slice (audit M12) — stage 1

The audit's most-leveraged finding: **zero** database or URL-level coverage. Flyway
migrations, native/JPQL queries, CHECK constraints and the `PUBLIC_PATHS` matcher list were
never executed by any test, so a bad migration or a query typo shipped with all 445 tests
green and failed only at prod startup or on the first real request.

- **Status:** 🔨 stage 1 written; **unverified by me** — see below
- **Migration:** none
- **PWA:** 0.35.1 (unchanged)

## Deliberately staged

I cannot run Gradle in this sandbox, so every line here is written blind. Dumping the full
slice (migrations + queries + auth matrix) in one go would mean that if *anything* is off —
a missing dependency, a Boot-4 annotation that no longer exists, a NOT NULL column I guessed
wrong — the failure arrives as a wall of unrelated errors on someone else's machine.

So stage 1 is the **harness plus the one test that needs no fixtures**. It is where almost
all the risk lives (deps resolve? Docker reachable? context starts?) and, conveniently, also
where most of the value lives. Stages 2–3 build on a harness already proven to run.

## What is here

**`IntegrationTestBase`** — `@SpringBootTest` + `@ActiveProfiles("test")` against a real
`postgres:17-alpine` via `@ServiceConnection`.

- **Postgres, never H2.** The migrations use PostgreSQL-specific SQL (partial indexes,
  `DO $$` blocks, `md5()`); an H2 shim would either fail or, worse, pass on SQL production
  rejects.
- **One `static` container for the whole run**, started by hand rather than via `@Container`,
  so subclasses share it instead of paying ~5 s each. The trade-off is a shared schema, so
  tests must create what they need rather than assume an empty database.
- **Named `…IntegrationTest`, not `…IT`** — discovery is name-based, and a class that
  silently never runs is worse than no test: it reports coverage that does not exist.

**`FlywayMigrationsIntegrationTest`** — proves the whole chain applies from an empty DB:
no failed rows in `flyway_schema_history`, and — since `ddl-auto: validate` runs during
context startup — an entity/column mismatch fails the context before any assertion. Plus two
targeted guards for migrations written blind in this run:
- V66 really renamed `token` → `token_hash` on both token tables;
- V65's **lookup-based** constraint drop really worked, i.e. `payments.period` now accepts
  `YEAR`. That migration had to drop a constraint whose name Postgres generated; if the
  lookup had matched nothing the drop would have silently no-opped and annual checkouts would
  fail at INSERT in production.

## Why the pre-flight checks mattered

Before writing anything I verified the context could actually start under `test`:
`JWT_SECRET` is the only property with no default, and `application-test.yml` overrides
`app.jwt.secret` directly; `AdminSeeder` returns early without `ADMIN_EMAIL`/`ADMIN_PASSWORD`;
`DevDataSeeder` is `@Profile("dev")` so it cannot pollute the test database.

## Stage 2 — the money queries (`ObjectEconomyQueriesIntegrationTest`)

The counted-income sums are `nativeQuery = true`, so **no Mockito test can reach them** — a
mocked repository returns whatever it is told. That is precisely how M6 survived unnoticed.
Until this file, the `AND e.status <> 'REJECTED'` guard from
[batch C](iteration-audit-batch-c.md) was the one fix in the entire audit sweep with no test
at all.

Covered: a REJECTED estimate is excluded **even while its flag says otherwise** (the exact
state V57 left every row in); unflagged variants stay out so econom/premium don't
double-count; works + materials still **add up to** the income figure (so the guard can't be
added to one query and forgotten in another); an empty object sums to `0`, not `null` —
`COALESCE` is load-bearing, a null would NPE on unboxing.

Because the container is shared, each test builds its own owner + object and scopes its
assertions to that `projectId` rather than assuming an empty database.

## Stage 3 — the security matrix (`SecurityMatrixIntegrationTest`)

Every existing controller test uses standalone MockMvc, which contains **no Spring Security
at all**, and `AdminAccessTest` only checks that `UserPrincipal` emits the right role
strings. So the real filter chain had never been exercised: a stray `/api/**` in
`PUBLIC_PATHS` or a dropped `hasRole("ADMIN")` would have shipped green.

`RANDOM_PORT` + the **plain JDK `HttpClient`**. Boot 4 removed the test-slice annotations
*and* `TestRestTemplate` (Spring 7 offers `RestTestClient` instead, and `@LocalServerPort`
moved packages in the same reshuffle). Rather than spend another build cycle guessing at
replacement test APIs, this reads `local.server.port` off `Environment` and speaks HTTP with
the JDK — core, stable API that cannot rot the next time Boot reorganises its test modules,
and a genuine socket is what the test is asserting about anyway.

Assertions are about the gate ("was I let in"), never a success body.

Two things were caught while writing it, before the build ever ran:
- An unknown path is **401, not 404** — `anyRequest().authenticated()` catches it first. The
  quiet 404 only applies under a permitted prefix, so the scanner-probe test targets
  `/admin/phpinfo.php` (where `/admin/**` is `permitAll`), which is the real-world case.
- `getStatusCode()` returns `HttpStatusCode`, not `HttpStatus`; comparing int values sidesteps
  a pointless cast that could have thrown at runtime.

## Build-cycle 3: the matrix found a real bug on its first run

Every unauthenticated request answered **403, not 401**. No `AuthenticationEntryPoint` was
ever configured, so Spring Security used its default `Http403ForbiddenEntryPoint` — the
fallback whenever no form/basic login is registered. Consequences, none of which any existing
test could see (standalone MockMvc has no Security in it at all):

- It contradicted the status mapping this project documents in `CLAUDE.md` ("401 —
  `BadCredentialsException`, `InvalidTokenException`, any other `AuthenticationException`").
  The advice mapped those correctly; requests that never reached a controller did not.
- **403 was overloaded.** The API issues 403 deliberately for plan limits
  (`*_LIMIT_REACHED`), `EMAIL_NOT_VERIFIED`, and cross-owner access. "Log in again" and "your
  plan forbids this" were the same status — the PWA's outbox already had to work around this
  by sniffing the `code` field (see the comment in `outbox/init.ts`).
- **The PWA refreshes on 401 only** (`status !== 401` → bail, in `api/client.ts`). So a token
  the server rejected — clock skew, revoked, rotated away, `JWT_SECRET` changed on redeploy —
  never triggered a refresh. The request just failed. The request interceptor's *proactive*
  refresh (it renews before sending when it can see the token is expired locally) masked this
  most of the time, which is why it survived to production.

Fixed with `RestAuthenticationEntryPoint` (401) + `RestAccessDeniedHandler` (403) wired via
`exceptionHandling`. `ExceptionTranslationFilter` routes anonymous requests to the entry point
and authenticated-but-denied ones to the handler, so the split lands exactly where it should:
no/bad token → 401, valid non-admin token on `/api/admin/**` → 403. Both emit the normal
`ErrorResponse` shape, localized via `requestLocale` (they run before the DispatcherServlet
sets the locale context — same rule as the rate-limit filters).

The test asserts **both halves**, plus a control that the minted token is genuinely accepted
elsewhere — otherwise "403" could just mean the test built a broken token, and a future change
could collapse the two statuses in either direction unnoticed. The admin HTML treats 401 and
403 identically (`logout()`), so it needed no change.

## Build-cycle 1: Testcontainers 2.x renamed its modules

`org.testcontainers:junit-jupiter` and `…:postgresql` resolved to **no version at all**
under Boot 4's BOM. Reading the actual `testcontainers-bom-2.0.5.pom` that
`spring-boot-dependencies` imports showed why: 2.x prefixed **every** module —
`testcontainers-postgresql`, `testcontainers-junit-jupiter`, and so on.

While fixing it, `testcontainers-junit-jupiter` was dropped entirely rather than renamed:
it exists only to provide the `@Testcontainers`/`@Container` lifecycle, and the container
here is a manually started static singleton, so the annotation was inert. One fewer
dependency, one fewer thing to be wrong.

## Gotchas
- **Docker is now required to build.** Without a daemon these tests fail rather than skip —
  deliberate: a green build must mean the migrations really ran. CI is fine (ubuntu-latest
  ships Docker); a local `./gradlew build` needs Docker Desktop running.
- `spring-boot-testcontainers` is declared explicitly. Boot 4 splits these per-feature modules
  out of the starters, and `@ServiceConnection` lives in that module — the class-not-found
  would otherwise look like a Testcontainers problem rather than a missing dependency.
- Build time grows by the container start (~5 s once, not per class).
