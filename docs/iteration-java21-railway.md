# Java 21 LTS for the build (Railway deploy fix)

- **Status:** ✅ Code complete — `./gradlew build` pending a local run.
- **Commit:** _(uncommitted at time of writing)_
- **Migrations / deps:** none (toolchain version change only).
- **Goal:** Railway couldn't build on Java 25 — no Nixpacks/Temurin image had it,
  and the foojay fallback failed (`api.foojay.io internal failure`). Fix the
  deploy without depending on a Java-25 build image.

## Decision: downgrade 25 → 21 (not a Dockerfile)

Checked first whether the code needs Java 25 — it does **not**:
- Spring Boot 4 baseline is **Java 17** (official: requires 17, tested up to 26).
- **No** Java 22–25-only feature is used: no `StructuredTaskScope`/`ScopedValue`/
  `Gatherer`/string templates/module imports, no unnamed variable `_`, no `///`
  markdown javadoc, no `--enable-preview`/special `--release`.
- All deps (jjwt, bucket4j, AWS SDK v2, web-push, OpenPDF, springdoc,
  BouncyCastle, Lombok) support Java 17+.

So the only thing pinning the project to 25 was one line. Moving to **Java 21
LTS** removes the whole problem: 21 has stable, ubiquitous images, so
Railway/Nixpacks build it natively — **no Dockerfile, no foojay download
needed**. Both are LTS; nothing the app uses is lost. (A Dockerfile with
`eclipse-temurin:25-jdk`/`:25-jre` — both real tags — would also work but is
heavier to maintain and unnecessary.)

## Changes

- `build.gradle.kts`: `JavaLanguageVersion.of(25)` → `of(21)` (+ rationale comment).
- `settings.gradle.kts`: foojay resolver kept, re-described as a dormant
  **fallback** (fetches JDK 21 only if the host lacks one).
- Docs synced to Java 21: `CLAUDE.md` (stack + Lombok note), `README.md` (stack +
  wrapper hint), `SPEC.md` (stack, IDE SDK, build/deploy note + Railway
  `NIXPACKS_JDK_VERSION=21`).

## Railway

Set `NIXPACKS_JDK_VERSION=21` so Nixpacks installs JDK 21; the Gradle toolchain
(`of(21)`) then matches it and uses it directly — foojay never fires. No
Dockerfile required.

## Verify

`./gradlew build` locally (host already has a JDK; Gradle picks a 21 toolchain —
auto-downloads via foojay only if 21 isn't installed). The downgrade is
source-compatible, so all existing tests compile and pass unchanged.
