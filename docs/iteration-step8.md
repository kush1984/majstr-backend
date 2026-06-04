# Step 8 — Web Push notifications for the contractor

- **Status:** ✅ Code complete — `./gradlew build` + app restart (V21) pending a
  local run. **Build verification is required this step:** it adds a new
  external dependency (`nl.martijndwars:web-push`) the agent's sandbox can't
  resolve, so `./gradlew build` must pass before this is trusted green.
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** `V21__create_push_subscriptions_table`
- **Goal:** the contractor gets a real-time browser notification when a client
  **signs an estimate** or **leaves a question** in the public portal — no more
  "learn about it only by refreshing". **Backend only** — the PWA service worker
  / subscribe button / iOS install hint are a separate task.

## 1. Dependency & config

- `build.gradle.kts`: `nl.martijndwars:web-push:5.1.2` (MIT, RFC 8291/8292).
  BouncyCastle pinned to `1.78.1` (`bcprov-jdk18on` / `bcpkix-jdk18on`) — the
  library ships 1.71 (2022); the newer build registers cleanly on Java 25 and
  the EC key-handling classes used are stable across versions.
- `VapidProperties` (`app.push.vapid.*`, env only): `publicKey`, `privateKey`,
  `subject`. `isConfigured()` is false when either key is blank → push is
  logged & skipped, mirroring the email transport. Registered in
  `MajstrApplication.@EnableConfigurationProperties`.
- `application.yml`: `app.push.vapid` block reading `VAPID_PUBLIC_KEY`,
  `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` (defaults blank / `mailto:admin@majstr.app`).
- `.env.example` documents the three vars + `npx web-push generate-vapid-keys`.

## 2. Model

- `PushSubscription` entity → `push_subscriptions` (`V21`): `id`,
  `user` (ManyToOne, FK `ON DELETE CASCADE`), `endpoint` (TEXT, **UNIQUE**),
  `p256dh`, `auth`, `user_agent` (nullable), `created_at`. Endpoint is unique so
  a re-subscribe upserts rather than duplicating.
- `PushSubscriptionRepository`: `findByEndpoint`, `findByUserId`,
  `deleteByEndpointAndUserId`.

## 3. Endpoints

`PushController` (`/api/push`):
- `GET /vapid-public-key` — **public** (added to `SecurityConfig.PUBLIC_PATHS`);
  returns `{ publicKey }` or `{ publicKey: null }` when unconfigured.
- `POST /subscribe` — Bearer JWT; `PushSubscriptionService.subscribe` upserts by
  endpoint (refreshes keys, reassigns to the current user). No duplicates.
- `POST /unsubscribe` — Bearer JWT; deletes by `(endpoint, userId)` — ownership
  enforced, you can only drop your own subscription.

DTOs: `PushSubscribeRequest` (endpoint/p256dh/auth/userAgent, validated),
`PushUnsubscribeRequest` (endpoint), `VapidPublicKeyResponse`. Swagger-annotated.

## 4. Sending

`PushService.sendToUser(User, title, body, url?)` — `@Async` + `@Transactional`,
fail-soft (a missing config / bad key / push-service error is logged, never
thrown). Loads all of the user's subscriptions and sends to each. On HTTP
**404 / 410** from the push service the dead subscription is **deleted**. The
underlying `nl.martijndwars.webpush.PushService` is built lazily once from the
VAPID keys and cached. `url` is a **relative** path (`/projects/{id}`) so the
service worker resolves it against the PWA origin.

## 5. Triggers (wired into the portal flow)

`PublicEstimateService`:
- `sign` → push title `"{signer} підписав(ла) кошторис на {amount} ₴"`, body =
  project name, url `/projects/{id}`. Amount formatted space-grouped, no
  decimals.
- `askQuestion` → push title `"Нове питання від клієнта"`, body = the question
  text, url `/projects/{id}`.

Both are fire-and-forget after the main action commits — a push failure never
breaks signing or question submission.

## Not changed (confirmed)

- Estimate / money / signing logic untouched (`sign` still flips the project to
  `IN_PROGRESS`, from Fix E). Question persistence unchanged.
- No email notification added — web push replaces the "Email notifications" open
  question for these two events (now RESOLVED).

## Tests

- `PushSubscriptionServiceTest` — subscribe stores a new row; re-subscribe with a
  known endpoint **reuses the same row** (no duplicate); unsubscribe deletes by
  `(endpoint, user)`.
- `PushServiceTest` — skips entirely when VAPID unconfigured; delivers to every
  subscription when configured; a **410 Gone** response prunes that subscription.
  The native send is stubbed via package-private seams (`createPushService` /
  `deliver`) so no real EC keys / network are needed.
- `PublicEstimateServiceTest` — extended: `sign` and `askQuestion` each invoke
  `PushService.sendToUser` with the expected title / project link (real send
  mocked).

## Open questions

- **Production web push (VAPID keys + iOS installed-PWA requirement)** — added to
  `open-questions.md`. A stable production keypair must be generated once; iOS
  only pushes to an installed (Home-Screen) PWA on 16.4+.
- A periodic sweep of dead `push_subscriptions` could join the future
  refresh-token / verification-token cleanup job (lazy 404/410 pruning covers it
  for now).

## Gotchas hit during live testing

- **Runtime-scope transitive types:** web-push declares Apache HttpClient and
  jose4j as `runtime` scope, but `send()` returns `org.apache.http.HttpResponse`
  and `throws org.jose4j.lang.JoseException` — both needed at *compile* time.
  Fixed by pinning `org.apache.httpcomponents:httpcore:4.4.16` and
  `org.bitbucket.b_c:jose4j:0.7.9` as explicit `implementation` deps (versions
  match what web-push pulls transitively).
- **FCM 403 → encoding:** `PushService.send(Notification)` defaults to the legacy
  `Encoding.AESGCM` (`Authorization: WebPush <jwt>`), which current Google FCM
  rejects with **HTTP 403**. `deliver()` now passes `Encoding.AES128GCM`
  explicitly (modern `vapid t=,k=` header). Diagnosed by reproducing the send
  with the reference Node `web-push` lib (same keys/subject/subscription → 201),
  which isolated the bug to the Java library's default encoding.
- **Keys are env-only:** the VAPID keypair lives in `.env` (gitignored), not in
  `application.yml` (which only has blank `${VAPID_*:}` defaults). When running
  from IntelliJ, the keys must be added to the Run Configuration / EnvFile —
  IntelliJ does not read `.env`.

## Build-verification gap

The agent cannot run Gradle in its sandbox (loopback socket blocked), and this
step adds a new dependency with transitive deps (BouncyCastle, Apache
HttpClient, jose4j). **Run `./gradlew build` locally** to confirm resolution +
compile before relying on this. The web-push API used: `new PushService(pub,
priv, subject)`, `new Notification(endpoint, p256dh, auth, payload)`,
`pushService.send(notification)` returning `org.apache.http.HttpResponse`.
