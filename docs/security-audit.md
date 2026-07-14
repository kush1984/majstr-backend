# Security audit — full-project sweep

A pass over the backend + PWA looking for real security gaps. **Bottom line: the
posture is strong** — no critical or high-severity exploitable gap found. Most
common vectors are already handled deliberately (evidence below). Three low-risk
hardening fixes were applied; a few minor observations are noted for the future.

## Verified secure (with evidence)

| Area | Finding |
|------|---------|
| **AuthN** | JWT HS256 via jjwt 0.12; `verifyWith(SecretKey)` + `requireIssuer` + expiry — resistant to `alg:none` / algorithm-confusion. Secret ≥32 bytes enforced (`JwtProperties`). |
| **AuthZ / IDOR** | Every owner endpoint scopes by `principal.id()` → `loadOwned(...)`. Admin routes `hasRole("ADMIN")`. Public portal photo stream is scoped by `findByIdAndProjectId` **and** `visibility == SHARED` — no cross-object/private leak. All ids are random UUIDs (no enumeration). |
| **SQL injection** | All queries parameterized (JPQL + 7 native queries in `EstimateRepository` use `:params`, no string concat). |
| **Path traversal** | `FileController` rejects `..` keys **and** `LocalStorageService.resolve()` normalizes + checks `startsWith(root)` — double-defended. |
| **File upload → stored XSS** | Photos/logos validated by **magic bytes** (`ImageContentTypeDetector`: PNG/JPEG only), content-type is **server-determined** (never client-supplied), served with Spring's default `X-Content-Type-Options: nosniff`. A fake-content-type HTML upload is rejected. |
| **Secrets** | `passwordHash`/`cardToken` never in any response (`UserResponse`, `@ToString(exclude=…)`). No secrets logged in prod paths (only dev-profile `DevDataSeeder` + Resend dev-fallback log the verify link when the key is blank). BCrypt(12); refresh tokens SHA-256-hashed at rest + rotated. |
| **Actuator** | Only `health` exposed, `show-details: never` — no env/beans/heapdump leak. |
| **CORS** | No wildcard; prod requires explicit `APP_CORS_ORIGINS` (fails fast if unset). `allowCredentials` with specific origins only. |
| **XML/Excel (XXE)** | Apache POI 5.3.0 — hardened XML factories (external entities disabled) + zip-bomb ratio guard by default. |
| **Rate-limit IP spoofing** | Prod sets `forward-headers-strategy: framework`; Spring's `ForwardedHeaderFilter` applies the real client IP to `getRemoteAddr()` **and strips** `X-Forwarded-For`, so the filters key off the genuine IP. The manual first-XFF parse is only reached in dev (no security need). Correct by design. |
| **Webhook** | monobank webhook signature-verified before any state change; `/api/billing/webhook` public only because it self-verifies. |
| **CSRF** | N/A — stateless Bearer-JWT in the `Authorization` header (not cookies). |
| **Enumeration** | Login returns a generic "invalid email or password"; portal token failures all collapse to a uniform 404. |
| **Anti-abuse** (this session) | Trial + paid PRO + client PDF require a verified email; registration blocks disposable domains + non-mail (MX) domains + gmail-alias duplicates. |

## Fixes applied (low-risk, static-HTML only)

1. **Admin panel Chart.js pinned with SRI** — the admin HTML loaded Chart.js from
   jsDelivr with no integrity check; a compromised/MITM'd CDN could have injected JS
   into the privileged admin context (admin JWT). Added
   `integrity="sha384-…"` (hash computed from the served bytes) + `crossorigin` +
   `referrerpolicy`. The browser now runs the script only if the bytes match.
2. **Portal `Referrer-Policy: no-referrer`** — the portal URL carries the share
   token (`?t=…`); the meta prevents leaking it via the `Referer` header to any
   external resource/link.
3. **Admin `Referrer-Policy: no-referrer` + `robots noindex`** — defense-in-depth.

## Minor observations (not gaps — future hardening, optional)

- **Register email enumeration** — `409` on an existing email reveals it's registered.
  Common product tradeoff (users expect "email already used"); left as-is.
- **Admin panel has no CSP** — it uses inline scripts, so a strict CSP would need
  `'unsafe-inline'` (low value) or refactoring the inline JS. Deferred.
- **No AI-call per-user/day quota** — a cost ceiling, not a security gap; tracked in
  `open-questions.md` (option E).
- **`DevDataSeeder` logs seed passwords** — dev profile only; acceptable.

## Not changed

Rate-limit IP resolution and `SecurityConfig` were left untouched — the current
design is correct (see the table) and any change there is deployment-topology
sensitive with real over-blocking risk. No speculative changes were made.
