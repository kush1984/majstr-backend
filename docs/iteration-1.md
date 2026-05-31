# Iteration 1 — Backend foundation (auth)

- **Status:** ✅ Done
- **Commits:** `880f6f3` (init) + foundation fixes `08b6a7e` (Spring Boot 4 /
  Spring 7 / Jackson 3 deprecations), `9e085b5` (replace removed test slices
  with pure Mockito), `fde9389` (pull `spring-boot-flyway` explicitly),
  `54c5959` (slash commands, untrack local settings)
- **Migrations:** `V1__create_users_table`, `V2__create_refresh_tokens_table`
- **Goal:** stand up the project skeleton and a complete, stateless JWT auth
  flow that every later iteration builds on.

## Chunks

### Project skeleton
- Spring Boot 4.0.6 (Spring Framework 7, Jakarta EE 11) on Java 25, Gradle
  Kotlin DSL, toolchain pinned to JDK 25.
- `MajstrApplication` — `@SpringBootApplication`, registers
  `@ConfigurationProperties` records.
- Package layout established: `config / controller / service / repository /
  entity / dto / security / exception` with the layering rule
  `controller → service → repository` (entities never leave the service
  layer; controllers return DTOs).

### PostgreSQL + Flyway
- `docker-compose.yml` runs PostgreSQL 17, credentials from `.env`.
- Schema owned by Flyway; Hibernate runs with `ddl-auto: validate`.
- `V1` users table (incl. `trade` CHECK constraint, `logo_url`, profile
  columns); `V2` refresh_tokens table with UNIQUE `token_hash`.
- Gotcha captured: `spring-boot-flyway` must be declared explicitly — Spring
  Boot 4 split it out of the starter graph, and without it Flyway silently
  no-ops and Hibernate validation then fails.

### User entity
- `User` — Lombok JPA entity, `@EqualsAndHashCode(of = "id")`,
  `@ToString(exclude = "passwordHash")`. `passwordHash` never appears in any
  response.
- `Trade` enum (ELECTRICAL / PLUMBING / TILING / GENERAL / OTHER), single-
  valued at this stage.

### JWT auth: register / login / refresh / me
- `AuthService.register` — BCrypt(12) hash, persist user, issue access +
  refresh tokens.
- `AuthService.login` — credential check, issue token pair.
- `RefreshTokenService.rotate` — rotation pattern: revoke the old refresh
  token, issue a new pair.
- `JwtService` — HS256 via jjwt 0.12.x, secret from env only.
- Refresh tokens hashed at rest: raw = 48 random bytes base64url, returned
  once on issue; DB stores only the SHA-256 hash, looked up by re-hashing the
  incoming token.
- DTOs (records, `jakarta.validation`): `RegisterRequest`, `LoginRequest`,
  `RefreshTokenRequest`, `AuthResponse`, `UserResponse`.
- Endpoints: `POST /api/auth/register`, `POST /api/auth/login`,
  `POST /api/auth/refresh`, `GET /api/auth/me`.

### Spring Security 7 (stateless)
- `SecurityConfig.filterChain` — lambda DSL only (Spring 7 removed chained
  forms), stateless sessions.
- `JwtAuthenticationFilter` parses the Bearer token, loads the user, sets
  `SecurityContextHolder` with `UserPrincipal`.
- `CustomUserDetailsService` for username/password lookups.
- Public paths whitelisted in `SecurityConfig.PUBLIC_PATHS`; everything else
  requires authentication.
- CORS via `CorsConfigurationSource` fed by `CorsProperties.allowedOrigins`.

### Global error handling
- `GlobalExceptionHandler` (`@RestControllerAdvice`) maps to the shared
  `ErrorResponse` shape `{ timestamp, status, error, message, path,
  retryAfterSeconds? }`. Null fields stripped globally
  (`default-property-inclusion: non_null`).
- Status mapping: 400 (validation / unreadable body), 401 (bad credentials,
  unknown user, invalid token), 403, 409 (`EmailAlreadyExistsException`),
  500 fallback.
- Typed exceptions: `EmailAlreadyExistsException`, `InvalidTokenException`,
  `ResourceNotFoundException`.

### Rate limiting on /login
- `LoginRateLimitFilter` (Bucket4j) runs before `JwtAuthenticationFilter`, so
  login is throttled even with no JWT. ~5 attempts / 15 min.
- Bucket key = `lowercased-email + "|" + clientIp` (clientIp respects the
  first `X-Forwarded-For` entry). Buckets in a `ConcurrentHashMap`
  (single-instance limitation, logged in open questions).
- `CachedBodyHttpServletRequest` buffers the JSON body so the filter can read
  `email` for the key **and** `@RequestBody` can still read it downstream.
- 429 is written directly by the filter (bypasses MVC), with
  `retryAfterSeconds` set via `ErrorResponse.rateLimited(...)`.

### Swagger, README, base tests
- springdoc-openapi 2.8.x, `OpenApiConfig`. Swagger UI at
  `/swagger-ui.html`.
- `AuthControllerTest` — pure Mockito + `MockMvcBuilders.standaloneSetup`,
  `GlobalExceptionHandler` registered manually. (Spring Boot 4 removed all
  test-slice annotations — see CLAUDE.md *Testing*.)

## Notes / gotchas
- JWT secret comes from env only, no default — startup fails fast if unset.
  HS256 requires ≥ 32 bytes; validated by `JwtProperties`.
- Jackson 3 lives under `tools.jackson.*`; the `com.fasterxml.jackson.*`
  classes on the classpath are for jjwt's internal use only.
- `-parameters` compile flag and `open-in-view: false` set here and relied on
  by all later iterations.
