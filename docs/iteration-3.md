# Iteration 3 — PDF + client portal

- **Status:** ✅ Done
- **Commit:** `bb765ab` (Add PDF, client portal, logo upload, FeatureGuard
  scaffold)
- **Migrations:** `V8__create_estimate_share_links_table`,
  `V9__add_signature_columns_to_estimates`,
  `V10__create_estimate_questions_table`
- **Goal:** turn an estimate into something a contractor can send to a client
  — a branded PDF and a public, no-login portal where the client can view,
  sign, and ask questions.

## Chunks

### Logo upload + file storage
- `StorageService` interface with `LocalStorageService` impl (the only impl;
  S3/R2 is a deferred open question).
- `FileController` → upload/serve endpoints; `ProfileController` →
  `/api/profile` (logo).
- `ImageContentTypeDetector` validates by **magic bytes**, not the declared
  content type; `UnsupportedMediaTypeException` on mismatch.
- `StorageProperties` for storage config.

### PDF generation
- `EstimatePdfService` renders an estimate to PDF.
- `PdfFontProvider` ships DejaVu/Roboto so **Cyrillic renders correctly** —
  the default PDF fonts do not cover Ukrainian.
- Branded vs basic PDF is gated by plan/feature (see FeatureGuard below).

### Share links
- `EstimateShareLink` — unique `token` per estimate.
- `ShareLinkService` mints/looks up links; `EstimateShareLinkRepository`.
- Endpoint to create a share link returns `ShareLinkResponse`.
- Note: the share token is stored **raw** (so the contractor can re-copy the
  URL) — a deliberate trade-off logged as an open question vs. hashing.

### Public portal endpoints
- `PublicEstimateController` → `/api/public/estimates/{token}` (no auth).
- `PublicEstimateService` returns a redacted view (`PublicEstimateView`,
  `PublicEstimateItemView`) — only what the client should see, no owner data
  leakage.
- `V9` adds signature columns to estimates; `V10` adds the
  `estimate_questions` table.

### Online signing + client questions
- Sign endpoint sets the signature columns and flips status to SIGNED
  (`SignRequest`).
- Ask-a-question endpoint persists `EstimateQuestion`
  (`QuestionRequest/Response`); `EstimateQuestionRepository`.

### Portal web page
- Adaptive vanilla page served from `static/portal/index.html` (no framework)
  — the client-facing view that consumes the public endpoints.

### FeatureGuard scaffold
- `FeatureGuard` interface + `Feature` enum + a no-op implementation as a
  placeholder. The real `DefaultFeatureGuard` replaces it in iteration 4
  **without changing the interface**.

### Public rate limiting + data-leak protection
- `PublicPortalRateLimitFilter` + `PortalRateLimiter` throttle public
  endpoints (separate process-local `ConcurrentHashMap` budget).
- `PortalProperties` for portal config.

### Tests
- `EstimatePdfServiceTest`, `PublicEstimateServiceTest`,
  `ImageContentTypeDetectorTest`.

## Notes / gotchas
- The portal limiter shares the multi-instance limitation of the login
  limiter (in-memory buckets).
- The redacted public view is the security boundary — keep owner/financial
  internals out of `PublicEstimateView`.
