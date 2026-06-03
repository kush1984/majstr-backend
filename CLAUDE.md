# Majstr Backend — Claude guide

SaaS backend for Ukrainian contractors. Current scope: authentication +
project foundation. See [README.md](README.md) for end-user setup and the
public REST contract; this file is for Claude / contributors working *in*
the codebase.

## Stack (pinned)

- **Spring Boot 4.0.6** (Spring Framework 7, Jakarta EE 11) on **Java 25**
- **Gradle Kotlin DSL** — toolchain pinned to JDK 25 in `build.gradle.kts`
- **PostgreSQL 17** via `docker-compose.yml`, schema owned by **Flyway**
- **Spring Security 7**, stateless, JWT via **jjwt 0.12.x** (HS256)
- **Bucket4j 8.x** (`bucket4j_jdk17-core`) — login rate limiting
- **Jackson 3** — **note the package change** (see *Gotchas*)
- **Lombok**, **springdoc-openapi 2.8.x**, **JUnit 5 + MockMvc**

Don't bump these without a clear reason — the combo is chosen for Spring
Boot 4 / Spring 7 / Jakarta EE 11 / Java 25 compatibility.

## Common commands

```bash
# bring Postgres up (requires .env with POSTGRES_PASSWORD)
docker compose up -d

# run the app (env vars from .env must be exported)
./gradlew bootRun

# tests
./gradlew test

# full build with verification
./gradlew build
```

JWT secret and DB credentials come from **env vars only** — never hardcode.
The base `application.yml` references `${JWT_SECRET}` with no default;
startup fails fast if it isn't set.

## Package layout

```
com.majstr.backend
├── MajstrApplication.java     — @SpringBootApplication, registers @ConfigurationProperties
├── config/                    — SecurityConfig, OpenApiConfig, *Properties records
├── controller/                — REST endpoints (thin, delegate to services)
├── service/                   — business logic, @Transactional boundaries
├── repository/                — Spring Data JPA interfaces
├── entity/                    — JPA entities (Lombok-annotated)
├── dto/                       — request/response **records**, validated with jakarta.validation
├── security/                  — JwtService, filters, UserPrincipal, body-cache wrapper
└── exception/                 — typed exceptions + GlobalExceptionHandler
```

Layering rule: `controller → service → repository`. Entities never leave
the service layer — controllers return DTOs. `passwordHash` never appears
in any response (`UserResponse` excludes it; `User#toString` excludes it).

## Architecture notes (non-obvious)

### Auth flow

1. `POST /api/auth/register` — `AuthService.register` hashes via BCrypt(12),
   persists the user, issues access + refresh tokens.
2. `POST /api/auth/login` — `LoginRateLimitFilter` runs first
   (`addFilterBefore(UsernamePasswordAuthenticationFilter)`), then the
   controller delegates to `AuthService.login`.
3. `POST /api/auth/refresh` — `RefreshTokenService.rotate` revokes the old
   refresh token and issues a new pair (rotation pattern).
4. `GET /api/auth/me` — `JwtAuthenticationFilter` parses the Bearer token,
   loads the user, sets `SecurityContextHolder` with `UserPrincipal`.

### Refresh tokens are hashed at rest

Raw refresh token = 48 random bytes, base64url-encoded, returned to the
client **only once** on issue. The DB stores only its SHA-256 hash
(`refresh_tokens.token_hash`, UNIQUE). On `/refresh`, the incoming raw
token is re-hashed and looked up by hash. `revoked = true` is set on the
old row before issuing the new one. Don't change this to store raw tokens.

### Email verification is soft

`register` issues an `EmailVerificationToken` (24h) and emails it via
`EmailService` → `ResendEmailService` (Resend HTTP API). The send is
`@Async` and **fail-soft** — a mail error is logged, never breaks
registration. Existing users were set verified in V19, so the gate can't
lock anyone out retroactively. Login and general use are **not** gated;
only `POST /api/estimates/{id}/share` requires `emailVerified` (else 403
`code: EMAIL_NOT_VERIFIED`). `POST /api/auth/verify-email` is public;
`POST /api/auth/resend-verification` is authenticated and rate-limited
(1/60s/user). Resend env vars: `RESEND_API_KEY` (blank in dev → email is
logged & skipped), `EMAIL_FROM`, `APP_URL` (verify-link base). Production
needs a Resend-verified sending domain in `EMAIL_FROM`.

### Login rate limit relies on a custom request wrapper

`LoginRateLimitFilter` needs to read the JSON body to extract `email` for
the rate-limit key, **and** Spring still needs to read it for `@RequestBody`.
Servlet input streams aren't re-readable, and `ContentCachingRequestWrapper`
doesn't replay reads — so we use a custom
[CachedBodyHttpServletRequest](src/main/java/com/majstr/backend/security/CachedBodyHttpServletRequest.java)
that buffers the body once and yields a fresh `ServletInputStream` per
`getInputStream()` call. The filter passes the wrapped request downstream.

Bucket key is `lowercased-email + "|" + clientIp`, where `clientIp`
respects the first entry of `X-Forwarded-For` if present. Buckets live in
a `ConcurrentHashMap` — fine for single-instance dev/prod but a known
limitation for multi-node deployments (would need a shared store).

### Spring Security 7 wiring

`SecurityConfig.filterChain` uses the lambda DSL only (Spring 7 removed
the deprecated chained forms). Both custom filters are added with
`addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)` —
order matters: `LoginRateLimitFilter` must run before
`JwtAuthenticationFilter` so login attempts are rate-limited even when no
JWT is presented. Public paths are listed in `SecurityConfig.PUBLIC_PATHS`;
everything else requires authentication.

CORS is configured via `CorsConfigurationSource` bean fed by
`CorsProperties.allowedOrigins` (comma-separated env var).

### Error response shape

All errors flow through `GlobalExceptionHandler` and use
[ErrorResponse](src/main/java/com/majstr/backend/dto/ErrorResponse.java):

```
{ timestamp, status, error, message, path, retryAfterSeconds?, code? }
```

`retryAfterSeconds` is set only by `ErrorResponse.rateLimited(...)` (the
login filter, the resend-verification 429 and the estimate-email 429).
`code` is an optional machine-readable code (`EMAIL_NOT_VERIFIED` on the
share gate; `CLIENT_EMAIL_MISSING` when emailing an estimate to a client
who has no address) so clients can branch without parsing the message.
Null fields are stripped globally via
`spring.jackson.default-property-inclusion: non_null`.

Status mapping:
- 400 — `MethodArgumentNotValidException`, `ConstraintViolationException`,
  `HttpMessageNotReadableException`
- 401 — `BadCredentialsException`, `UsernameNotFoundException`,
  `InvalidTokenException`, any other `AuthenticationException`
- 403 — `AccessDeniedException`
- 409 — `EmailAlreadyExistsException`
- 429 — emitted directly by `LoginRateLimitFilter` (does **not** go through
  the advice — it bypasses Spring MVC because the filter writes the
  response itself)
- 500 — fallback, with a logged stack trace

### Schema is owned by Flyway

`hibernate.ddl-auto: validate`. Never put schema changes in entity
annotations expecting Hibernate to apply them. Add a new
`V<N>__<desc>.sql` under `src/main/resources/db/migration/`. Trades live
in the `user_trades` collection table (one row per `(user_id, trade)`,
mapped via `User.trades` `@ElementCollection`); it has a `CHECK`
constraint enumerating the allowed values — if you add a `Trade` enum
constant, write a migration to extend that CHECK. (The old single-valued
`users.trade` column was dropped in V16.)

### Entities vs. records

- **Entities** (`User`, `RefreshToken`) — mutable JPA, Lombok
  `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`,
  `@EqualsAndHashCode(of = "id")` to avoid the lazy-loading pitfall.
  `@ToString(exclude = "passwordHash")` on `User`.
- **DTOs** — `record`s with `jakarta.validation` constraints on
  components. Don't mix Lombok with records.

## Gotchas

- **Spring Boot 4 split many auto-configs into per-feature modules** that
  starters do *not* pull transitively. So far we've hit:
  - **`spring-boot-flyway`** — required for Flyway auto-config. Without it
    Flyway silently does nothing, Hibernate then fails schema validation.
    Already declared in `build.gradle.kts`; don't remove it.
  - Test-slice annotations (`@WebMvcTest` etc.) are also gone — see
    *Testing* below.
  Expect similar surprises for other auto-configs (`spring-boot-liquibase`,
  `spring-boot-jpa-test`, `spring-boot-jdbc-test`, ...). Symptom is
  usually "feature X silently doesn't run" or a NoClassDefFound.
- **Jackson 3 package**: Spring Boot 4 ships Jackson 3, whose package is
  `tools.jackson.*` (not `com.fasterxml.jackson.*`). When injecting
  `ObjectMapper`, use `import tools.jackson.databind.ObjectMapper;`. The
  `com.fasterxml.jackson.*` classes may still be on the classpath
  (transitively via `jjwt-jackson`) — they're for jjwt's internal use,
  don't pull them into application code.
- **Lombok + Java 25**: works via the Spring Boot–managed Lombok version.
  If you bump Java further, verify Lombok supports it.
- **JWT secret length**: HS256 requires ≥ 32 bytes (256 bits). Validated
  by `JwtProperties` (`@Size(min = 32)`). Generate with
  `openssl rand -base64 48`.
- **`-parameters` compile flag** is enabled — required for
  `@PathVariable`/`@RequestParam` without explicit names and for Spring 6+
  parameter-name discovery. Don't remove it from `build.gradle.kts`.
- **`open-in-view: false`** — JPA sessions don't extend into the view
  layer. If you need a lazy association in a controller, fetch it
  explicitly in the service.

## Testing

**Spring Boot 4 removed all test-slice annotations** — `@WebMvcTest`,
`@AutoConfigureMockMvc`, `@DataJpaTest`, etc. are gone from every jar.
Tests therefore use one of two patterns:

1. **Controller tests** (`AuthControllerTest`) — pure Mockito with
   `@ExtendWith(MockitoExtension.class)` + `@Mock` / `@InjectMocks` +
   `MockMvcBuilders.standaloneSetup(controller)`. No Spring context, no
   DB, instant startup. `GlobalExceptionHandler` is registered manually
   via `.setControllerAdvice(...)` so the error mapping is exercised too.
2. **Integration tests** (not yet wired) — full `@SpringBootTest` against
   a **Testcontainers** PostgreSQL. Don't try H2; the Flyway migrations
   are PostgreSQL-specific.

`application-test.yml` still holds a test JWT secret for the day we add
an integration slice that loads the real context.

## Open-question log

[docs/open-questions.md](docs/open-questions.md) keeps deferred decisions
and known gaps across iterations — multi-instance state, billing,
PHOTO_REPORTS / AI_ASSISTANT, JWT key rotation, etc. There is a skill
at `.claude/skills/open-questions/SKILL.md` whose description says "use
at the start of every new iteration"; Claude should self-trigger it via
the Skill tool whenever the user kicks off a new step / feature, before
writing any code. It reads the file, classifies every `OPEN` item against
the upcoming work (in scope / adjacent / out of scope), and offers to
promote, close, or add items. Update statuses inline, don't rewrite the
file. When an item is closed, leave it in the file with `RESOLVED` +
a one-line note so the history is preserved.

## Conventions

- **Language**: all code, comments, log messages, SQL, YAML, and
  Markdown — **English only**. The user prefers chatting in Ukrainian but
  artifacts stay English.
- **Comments**: default to none. Write a one-liner only when the *why* is
  non-obvious (e.g. the body-cache wrapper rationale). Don't restate code.
- **Boundaries of change**: keep changes scoped. Bug fix ≠ refactor.
  Don't add abstractions for hypothetical future needs.
- **No backwards-compat shims** for code that hasn't shipped — this is a
  greenfield project; just change it.

## Not implemented yet

These are intentional gaps to be aware of (don't claim they exist):

- No `actuator` starter — `/actuator/health` is permitted in
  `SecurityConfig` for future use but the dependency isn't on the
  classpath yet.
- No scheduled cleanup of expired refresh tokens (the
  `RefreshTokenRepository.deleteExpired` query exists; nothing calls it).
- No `User` profile / logo upload endpoints — only the column is in V1.
- No integration tests (only the MockMvc slice).
- No multi-instance rate-limit store (in-memory `ConcurrentHashMap`).
- No estimates / projects / client-communication domain — that's the
  next iteration.
