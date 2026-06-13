# Fix L — Scanner 404s logged as 500s + reported to Sentry

- **Status:** ✅ Backend code + test complete — `./gradlew build` pending a local run.
- **Commit:** _(uncommitted at time of writing)_
- **Migrations / deps:** none.
- **Source:** Sentry issue `JAVA-SPRING-BOOT-8` —
  `NoResourceFoundException: No static resource admin/phpinfo.php` on
  `GET /admin/phpinfo.php` (prod), a bot/vulnerability scanner probe.

## Diagnosis

`/admin/phpinfo.php` (and similar) is **internet background noise** — an
automated scanner probing for a classic PHP info-disclosure file. Nothing
leaked (0 users impacted, Java app has no such resource). **Not an app
vulnerability.**

The real problem was the handling: `NoResourceFoundException` had no dedicated
`@ExceptionHandler`, so it fell through to the catch-all
`@ExceptionHandler(Exception.class) handleAny`, which:
1. returned **HTTP 500** instead of a 404 for an unknown path, and
2. called `reportToSentry(ex)` — so every scanner probe created a Sentry error
   event (the noise the user was seeing).

## Fix

Added a dedicated handler in `GlobalExceptionHandler`:

```java
@ExceptionHandler(NoResourceFoundException.class)
public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
    log.debug("No resource for {} {}", req.getMethod(), req.getRequestURI());
    return build(HttpStatus.NOT_FOUND, msg("error.not-found"), req);
}
```

- Unknown path → plain **404** (localized `error.not-found`), the correct status.
- **Not** reported to Sentry — it's noise, not a fault. `handleAny` (real 5xx)
  still reports as before.
- More specific than `Exception`, so Spring routes `NoResourceFoundException`
  here instead of the catch-all.

## Tests

`GlobalExceptionHandlerTest`: new `/scan` route throws
`new NoResourceFoundException(HttpMethod.GET, "admin/phpinfo.php")`; asserts the
advice returns **404** with the localized "Запис не знайдено" message (not the
500 fallback). The existing 5xx-no-leak / localization tests are unchanged.

## Notes

- After deploy, mark Sentry `JAVA-SPRING-BOOT-8` as Resolved (or "Resolve in
  next release"); it won't recur — scanner hits become quiet 404s.
- A broader inbound-filter / rate-limit on obvious scanner paths is possible but
  unnecessary; a 404 with no Sentry report is enough.

## Verify (after local build)

`./gradlew build` green; then `curl -i https://<host>/admin/phpinfo.php` → 404
JSON (`status: 404`), and no new Sentry event for it.
