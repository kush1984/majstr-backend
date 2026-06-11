# Majstr Backend

SaaS backend for Ukrainian contractors: estimate tracking and client
communication. Current scope: JWT auth with email verification, projects /
clients / personal catalog, estimates with PDF export and a public client
portal (share link, online signing, questions), plans & admin metrics, web
push notifications, health/monitoring. The full REST surface is documented
in Swagger (`/swagger-ui.html`); the tables below cover only the auth and
push basics.

## Stack

- Spring Boot **4.0.6** (Spring Framework 7) on Java **21** (LTS)
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
> `gradle wrapper --gradle-version 9.3` — you'll need a system Gradle
> 8.5+ for Java 21 toolchain support.

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
    "trades":["ELECTRICAL"],
    "phone":"+15551234567",
    "companyName":"Smith Electrical LLC"
  }'
```

Response: `accessToken` (15 min) + `refreshToken` (rotating, 30 days by
default — `REFRESH_TOKEN_TTL_DAYS`) + `user`.

### Example: authenticated request

```bash
curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer <accessToken>"
```

## Rate limiting

`POST /api/auth/login` is capped at **5 attempts per 15 minutes** keyed by
`email + IP`; `POST /api/auth/register` at **5 per hour** keyed by IP.
When exceeded the server returns HTTP 429 with a structured error body and
a `Retry-After` header (seconds until the bucket refills).

## Health check

`GET /actuator/health` is public and reports the aggregate status
(`UP`/`DOWN`, database included) without internal details. Probe endpoints
for orchestrators: `/actuator/health/liveness` and
`/actuator/health/readiness` (readiness turns `DOWN` when the DB is
unreachable).

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

## Web Push (notifications)

The contractor receives a real-time browser notification when a client
**signs an estimate** or **leaves a question** in the public portal. This
uses the Web Push standard (VAPID / RFC 8291).

| Method | Path | Description | Auth |
| --- | --- | --- | --- |
| GET  | `/api/push/vapid-public-key` | Server VAPID public key (`null` if unconfigured) | public |
| POST | `/api/push/subscribe` | Store/refresh this browser's subscription (upsert by endpoint) | Bearer JWT |
| POST | `/api/push/unsubscribe` | Remove this browser's subscription | Bearer JWT |

### VAPID keys

Push requires a VAPID keypair, supplied via environment (never hardcoded):

```bash
# generate once (Node tool — no install needed):
npx web-push generate-vapid-keys
```

Copy the output into `.env`:

```
VAPID_PUBLIC_KEY=BPx...        # base64url public key
VAPID_PRIVATE_KEY=k3F...       # base64url private key
VAPID_SUBJECT=mailto:admin@majstr.app
```

Leave them **blank in local dev** — the server then logs and skips each
push instead of sending. Generate the keypair **once** for production and
keep it stable: rotating it invalidates every existing browser
subscription, forcing all clients to re-subscribe.

> **iOS note:** Safari only delivers web push to a PWA **added to the Home
> Screen** (installed / standalone) on iOS 16.4+. A plain Safari tab gets
> nothing — the frontend should detect this and prompt the user to install.

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
