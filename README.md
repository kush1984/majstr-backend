# Majstr Backend

SaaS backend for Ukrainian contractors: estimate tracking and client
communication. This iteration covers authentication and the project
foundation only.

## Stack

- Spring Boot **4.0.6** (Spring Framework 7) on Java **25** (LTS)
- Gradle (Kotlin DSL)
- PostgreSQL **17** via Docker
- Spring Data JPA / Hibernate + Flyway
- Spring Security 7 + JWT (jjwt 0.12.x), stateless
- Bucket4j 8.x — rate limiting
- springdoc-openapi — Swagger UI at `/swagger-ui.html`
- Lombok, JUnit 5 + MockMvc

## Quick start

### 1. Set up environment variables

```bash
cp .env.example .env
# generate a real JWT secret:
openssl rand -base64 48
# paste it into JWT_SECRET in .env, then update POSTGRES_PASSWORD
```

### 2. Start the database

```bash
docker compose up -d
docker compose ps         # confirm the healthcheck reports "healthy"
```

Postgres listens on `localhost:5432`; data is kept in the named volume
`majstr_pgdata` and survives container restarts.

### 3. Run the application

Make sure the variables from `.env` are exported into your shell
(`set -a; source .env; set +a` in bash, or an IDE run configuration),
then:

```bash
./gradlew bootRun
```

> If you don't have `./gradlew` yet, generate it once with
> `gradle wrapper --gradle-version 8.14` — you'll need a system Gradle
> 8.10+ for Java 25 toolchain support.

The server listens on `http://localhost:8080`. Flyway runs `V1` and `V2`
automatically on startup.

### 4. Swagger UI

Open `http://localhost:8080/swagger-ui.html`.

## REST API (auth)

| Method | Path | Description | Auth |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | Register a new contractor | public |
| POST | `/api/auth/login` | Issue access + refresh tokens | public, 5/15min rate limit |
| POST | `/api/auth/refresh` | Exchange refresh for a new pair | public |
| GET  | `/api/auth/me` | Currently authenticated contractor | Bearer JWT |

### Example: register

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email":"john@example.com",
    "password":"S3cret-pass!",
    "fullName":"John Smith",
    "trade":"ELECTRICAL",
    "phone":"+15551234567",
    "companyName":"Smith Electrical LLC"
  }'
```

Response: `accessToken` (15 min) + `refreshToken` (7 days) + `user`.

### Example: authenticated request

```bash
curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer <accessToken>"
```

## Rate limiting

`POST /api/auth/login` is capped at **5 attempts per 15 minutes** keyed by
`email + IP`. When exceeded the server returns HTTP 429 with a structured
error body and a `Retry-After` header (seconds until the bucket refills).

## Error format

Every error is returned in a single shape:

```json
{
  "timestamp": "2026-05-25T10:15:30Z",
  "status": 400,
  "error": "Bad Request",
  "message": "email: must be a well-formed email address",
  "path": "/api/auth/register"
}
```

`retryAfterSeconds` is included only on 429 responses.

## Package layout

```
com.majstr.backend
├── config/      — Spring Security, CORS, OpenAPI, @ConfigurationProperties
├── controller/  — REST endpoints
├── service/     — business logic
├── repository/  — Spring Data JPA
├── entity/      — JPA entities
├── dto/         — request/response records
├── security/    — JWT, UserDetails, filters
└── exception/   — global handler + typed exceptions
```

## Tests

```bash
./gradlew test
```

The base layer is `AuthControllerTest` (MockMvc) covering `register` and
`login`. Integration tests against a real PostgreSQL will come next via
Testcontainers.
