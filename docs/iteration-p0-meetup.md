# P0 before the 11 November meetup (backend + PWA 1.56.1)

**Source:** `C:\Work\prompts\fix-p0-prompt.md`, from `review-golden-path-2026-10.md` (17.10). Fixes only,
no new features. Every P0 was re-checked in code first; all six still held (line numbers had moved
after the 15.10 portal hotfix).

## 1. Portal rate limit — three buckets, smooth refill

`PortalRateLimiter` was ONE bucket per IP, 30/min, refilled all at once. A hall on one Wi-Fi or a
carrier's CGNAT is one IP for dozens of people on the same link. Now (all `refillGreedy`):

- **per IP + link token, reads** — `per-link-max-attempts` 120/min (`PORTAL_MAX_PER_LINK`);
- **per IP + link token, POSTs** (sign, question) — `write-max-attempts` 10/min
  (`PORTAL_MAX_WRITES_PER_LINK`); questions keep their own `QuestionRateLimiter` on top;
- **per IP, everything** — `max-attempts`, default 300/min (`PORTAL_MAX_PER_IP`; the Railway override
  `APP_RATELIMIT_PORTAL_MAXATTEMPTS=600` still binds to it). The defence against walking tokens.

The token is read off the path, `/api/public/<kind>/<token>/…` (`PublicPortalRateLimitFilter.linkToken`).
The portal page retries a 429 on load by itself, counting down the `Retry-After` («Забагато людей
одночасно — пробуємо ще раз за N с»). `PortalRateLimiterTest`: 60 reads of one link pass; 600
different links from one IP stop at the ceiling; writes stay strict.

**`X-Forwarded-For` was NOT verified** — it needs the prod DB (open question below).

## 2-3. Portal: after a signature, and a double tap

- The POST's answer is rendered (it IS the signed page); a second GET could fail after the signature
  had landed. Then «Дякуємо! Кошторис підписано {дата}. Майстер уже отримав сповіщення.» above the
  document; the act says «Приймання робіт підтверджено».
- «Підписати» is disabled with «Підписуємо…» while the request runs. A 409 `ESTIMATE_SIGNED` /
  `WORK_ACT_SIGNED` is shown as that same success; a 409 for a CHANGED document still reloads.
- On `/api/public/**` the 409 text is the client's («Цей кошторис уже підписано» /
  «Цей акт уже підтверджено»), not the master's «Створіть новий кошторис», and every answer there is
  Ukrainian whatever the phone's language (`LocalizationConfig.isPublic`).
  `PortalSignTwiceIntegrationTest`, `PortalSignFlowTest`.

## 4. New estimate: a retry made a second object and client

`NewEstimatePage` keeps what an attempt created (`useRef`) and reuses it; the FREE cap check is
skipped once the object exists; a failed BUNDLE takes the master into the created object instead of
back to a form whose «Створити» would make another. `NewObjectPage` reuses its client the same way.

## 5. A bundle applied offline blocked its own lines

Online the server applies a bundle and its lines are born with quantity 0. Offline the PWA queued the
same lines through the MANUAL door (`POST …/items`), which rightly refuses 0 — every line blocked for
good. **Chosen:** a door of their own, `POST /api/estimates/{id}/items/template-line`
(`TemplateLineRequest`: no quantity, price ≥ 0, never a «%»), going through the same
`EstimateService.addItem`. **Not** one queued «apply bundle» op: the server mints the line ids, so
any offline edit of a line before the sync would have pointed at an id the server does not have.
The replay picks the door by shape (`isTemplateLine`: quantity 0, not a «%») — so a line whose
quantity the master already typed offline goes the ordinary way, and the manual door's validation
is unchanged. **Already-blocked ops on masters' phones** are released on the next start
(`releaseBlockedTemplateLines`) and replay through the new door.

## 6. Act editor offline: no way out

Every waiting state has the «←» header (back to the object, else history, else the objects list).
Offline a never-fetched query is PAUSED, not failed — it spun forever; it now says «Немає зв'язку —
акт відкриється, щойно з'явиться мережа» with a retry; a real error shows `ErrorState`. The offline
prefetch now stores each object's acts list, every act and `act-progress`.

## Not run

- `npm run test:e2e:offline` → `journey.spec` could not register its user: the backend running on
  :8080 refused the e2e origin `localhost:4173` (CORS preflight 403). `shell.spec` passed. Re-run with
  the backend started from `.env` (its `CORS_ALLOWED_ORIGINS` includes :4173).
- The phone checks (double tap, signing on mobile data, act and bundle in flight mode).
