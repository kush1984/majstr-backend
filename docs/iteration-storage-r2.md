# S3/R2 storage backend (backend)

- **Status:** ✅ Backend code complete — `./gradlew build` pending a local run
  (Gradle isn't reachable from the agent's sandbox).
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** none.
- **Goal:** add a production object-storage backend (Cloudflare R2, S3-compatible)
  alongside the existing filesystem one, switchable by config — closing the
  long-standing "File storage migration to S3/R2" open question. **Backend only.**

## Interface: unchanged ✅

`StorageService` (`store` / `open` / `contentType` / `delete`, returning
`StoredObject`) was **not modified** — it was already a clean enough abstraction
to host a second impl, exactly as the open-questions note hoped. No caller
(`ProfileService`, `FileController`, `EstimatePdfService`) changed.

## Bean selection

- `StorageProperties` gained `kind` and an `s3` sub-record (`endpoint`,
  `accessKeyId`, `secretAccessKey`, `bucket`, `region`) + an `isS3()` helper.
- `StorageConfig` (new `@Configuration`) builds **exactly one** `StorageService`
  bean from `app.storage.kind` (`STORAGE_KIND`, default `local`). Imperative
  selection (not `@ConditionalOnProperty`) — explicit, and dodges Spring Boot 4
  auto-config surprises, like `LocalizationConfig`.
- `LocalStorageService` lost its `@Service` annotation; `S3StorageService` never
  had one. Neither is component-scanned, so the two beans can't collide. `@PostConstruct`
  on `LocalStorageService` still runs (Spring post-processes `@Bean` instances).
- The S3 client is created only on the `s3` branch, so **local dev never needs
  R2 credentials** and never opens a connection.

## S3StorageService

- AWS SDK v2 (`software.amazon.awssdk:s3`, BOM `2.46.8`), **sync** `S3Client`
  over `UrlConnectionHttpClient` (lightweight, no Netty). `forcePathStyle(true)`
  and region `auto` — R2's requirements. HTTP client set explicitly so the SDK
  never errors on "multiple HTTP implementations" if another lands on the classpath.
- Keys mirror local exactly: `prefix/uuid.ext` (extension lower-cased, `bin`
  fallback). So a `logoUrl` stored under local keeps resolving after a switch to R2.
- Content type is **native S3 object metadata** (set on PUT, read via HEAD) — no
  `.meta` sidecar like local needs.
- `open` returns the `ResponseInputStream` (an `InputStream`); `NoSuchKeyException`
  → `Optional.empty()`. `delete` is naturally idempotent on S3.

## Reads stay backend-served (consistent with FileController)

`FileController` (`/api/files/**`) already streams every object via
`storage.open(key)`, and the PDF reads the logo the same way. That holds for R2
unchanged, so **the bucket needs no public-read policy** — chosen over a public
bucket for simplicity and stable URLs. A direct-public / CDN read path is a
possible future optimization (noted, not done).

## Config / env

```
STORAGE_KIND=local            # or s3
R2_ENDPOINT=                  # https://<account>.r2.cloudflarestorage.com
R2_ACCESS_KEY_ID=
R2_SECRET_ACCESS_KEY=
R2_BUCKET=
R2_REGION=auto                # optional
```
Added to `application.yml` (`app.storage.kind` + `app.storage.s3`) and `.env.example`.

## Tests

- `S3StorageServiceTest` (mock `S3Client`): store puts under a `prefix/uuid.ext`
  key with the right bucket + content type and returns a matching descriptor;
  `bin` extension fallback; open returns the stream / empty on `NoSuchKey`;
  contentType from HEAD / empty on `NoSuchKey`; delete issues `DeleteObjectRequest`.
- `StorageConfigTest`: `kind=local` and missing `kind` → `LocalStorageService`;
  `kind=s3` → `S3StorageService` (building the client opens no connection).

## Not changed / confirmed

- `StorageService` interface, `LocalStorageService` behavior, `FileController`,
  `ProfileService` logo flow, `EstimatePdfService` logo read — all untouched.
- **local still works**: default `STORAGE_KIND=local` → `StorageConfig` returns
  `LocalStorageService` exactly as before (it just isn't `@Service`-scanned now).

## Gotchas / follow-ups

- The logo upload's `storage.store()` runs inside `ProfileService`'s
  `@Transactional`; with R2 that's a network call holding a DB connection —
  strengthens the existing "I/O inside @Transactional" open item (left OPEN).
- R2 keypair/bucket creation in Cloudflare is a deploy task (SPEC БЛОК 2).

## Build-verification note

Gradle can't run in the agent sandbox. Run `./gradlew build` locally — one new
dependency group (AWS SDK v2 BOM + `s3` + `url-connection-client`), no migration.
Smoke (local, default): app starts, logs "Storage backend: local filesystem",
logo upload + `/api/files/...` work as before. For R2: set `STORAGE_KIND=s3` +
`R2_*`, upload a logo, confirm it appears in the bucket and the portal/PDF render it.
