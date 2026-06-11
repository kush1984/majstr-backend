# Localization of user-facing messages + portal resilience (backend)

- **Status:** ✅ Backend code complete — `./gradlew build` pending a local run
  (Gradle isn't reachable from the agent's sandbox).
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** none.
- **Scope:** **Backend only.** Two things in one chunk: (1) localize every
  end-user-visible *message*, (2) the small leftover backend fix — friendly
  error handling on the backend-served vanilla client portal page.

## 1. Message localization (MessageSource)

- New `LocalizationConfig` — explicit `MessageSource` + `AcceptHeaderLocaleResolver`
  beans (not Boot auto-config, per the Spring Boot 4 module-split caution).
- Bundles: `messages.properties` is **Ukrainian** (the base file → fallback for
  every untranslated locale, so an unknown `Accept-Language` never leaks English
  internals); `messages_en.properties` is served only on explicit
  `Accept-Language: en`. `fallbackToSystemLocale=false` keeps the JVM locale out.
- `GlobalExceptionHandler` is now constructor-injected with the `MessageSource`
  and resolves every user-facing message through it:
  - Type-mapped keys (bad credentials, access denied, not-found, internal, …).
  - Exceptions that carry **context-specific text** now pass a **bundle key** as
    their message (`EmailNotVerifiedException`, `ClientEmailMissingException`,
    `InvalidVerificationTokenException`, `UnsupportedMediaTypeException`,
    `InvalidEstimateStatusException`, `TooManyRequestsException`) — the advice
    resolves `msg(ex.getMessage())`, falling back to the raw text for unknown
    keys so a stray literal can never 500.
  - `FeatureNotAvailableException` / `LimitExceededException` lost their
    hard-coded Ukrainian `buildMessage`; the advice now formats
    `error.feature.unavailable` / `error.limit.projects` from the carried
    fields. Ukrainian plural for "об'єкт" moved into
    `GlobalExceptionHandler.projectsPluralKey` (+ `plural.projects.*` keys).
- **Filters** (`LoginRateLimitFilter`, `RegisterRateLimitFilter`,
  `PublicPortalRateLimitFilter`) write their own 429 bodies before the
  DispatcherServlet sets a locale — they take the `MessageSource` and resolve
  via `LocalizationConfig.requestLocale(request)` (header → locale, no header →
  Ukrainian; never the JVM default). The endpoint-level 429s
  (`EstimateController`, `AuthController`) now throw bundle keys.
- **Push notifications** (`PublicEstimateService.sign`/`askQuestion`) build the
  title/body from `push.estimate-signed` / `push.question.title`, always in
  `LocalizationConfig.UKRAINIAN` — the contractor's notification is generated
  inside the *client's* request context, so the client's locale must not leak
  into it.

## 2. Portal resilience + sign-conflict (the "small fix")

- `static/portal/index.html`: load failures no longer dump a raw string into a
  blank page. A dead link (404) → "Посилання недійсне… зверніться до майстра"
  (no retry). A transient failure / network error → friendly
  "Не вдалося завантажити… спробуйте ще раз" **with a retry button**. `fetch`
  is wrapped in try/catch so a network drop is handled, not thrown. Sign /
  question failures surface the backend's localized `message` (or a fallback),
  and the typed question text is kept on failure (nothing lost).
- A double-sign (e.g. two tabs) now returns **409**: `PublicEstimateService.sign`
  throws the shared `EstimateSignedException` (was a bare `ResponseStatusException`),
  so the portal client gets the same localized 409 + `ESTIMATE_SIGNED` contract
  as the contractor side, and the page reloads to show the existing signature.
- Added the missing `LINEAR_METER` ("м.п.") unit to the portal's `UNIT_LABEL`.

## Deliberately NOT localized (product-language content, see open-questions)

The estimate **PDF** (`EstimatePdfService`), the **email HTML**
(`ResendEmailService`), the **portal page chrome** (button/section labels —
only its error states were localized) and **jakarta-validation field errors**
stay Ukrainian. These are product-language content/forms, not messages; the PWA
validates client-side with its own uk texts. Tracked as an OPEN item to revisit
only if a second client-facing language is ever needed.

## Tests

- `GlobalExceptionHandlerTest` rebuilt against the **real** bundle: generic 500
  with no leak; **uk by default**, **en on `Accept-Language: en`**, **unknown
  locale (de) → uk fallback**; `ESTIMATE_SIGNED` 409 localized; optimistic-lock
  409 localized; `LimitExceededException` → localized message with the correct
  Ukrainian plural ("2 об'єкти").
- `AuthControllerTest` / `ProjectControllerTest`: advice now constructed with a
  test `MessageSource`; assertions unchanged (status codes).
- `PublicEstimateServiceTest`: built with a real bundle (push texts);
  new `sign_rejectsAlreadySignedEstimateWith409Semantics`.
- `ImageContentTypeDetectorTest`: asserts the bundle **key** is thrown
  (`error.upload.type`); `LimitServiceTest`: asserts the carried fields
  (message text now lives in the bundle, covered by the advice test).

## Build-verification note

Gradle can't run in the agent sandbox. Run `./gradlew build` locally — no new
dependency, no migration. The bundle files are `src/main/resources/messages*.properties`.
Smoke: a 4xx/5xx with `Accept-Language: en` returns the English message; without
the header (or with `uk`/anything else) it's Ukrainian. Open a portal link with
the backend stopped → friendly retry screen, not a blank page.
