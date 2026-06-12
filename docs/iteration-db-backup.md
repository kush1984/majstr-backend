# Daily DB backups → Cloudflare R2 (GitHub Actions)

- **Status:** ✅ Workflow + restore doc complete. Needs secrets configured and a
  first manual run + restore drill to be considered "live".
- **Commit:** _(uncommitted at time of writing)_
- **Migrations / deps:** none (CI + docs only; no app code touched).
- **Goal:** Close the SPEC §H production-DB backup blocker without a paid
  service — automated daily logical backups stored off-platform in R2, with a
  proven restore procedure.

## What shipped

- **`.github/workflows/db-backup.yml`** — scheduled (`cron: "0 3 * * *"`, 03:00
  UTC) + `workflow_dispatch`. Single job:
  1. Install the **PostgreSQL 18 client** from the PGDG apt repo (prod is
     PostgreSQL 18 on Railway; `pg_dump` must be ≥ server). **Gotcha:** the
     runner preinstalls the v16 client meta-package which owns
     `/usr/bin/pg_dump`; `apt-get install postgresql-client-18` installs 18
     *alongside* it and pg_dump stays 16 ("server version mismatch"). Fix:
     install the meta pinned to 18 — `postgresql-client=18.*` — which replaces
     the 16 client in place, so `pg_dump --version` → 18.x. Runner pinned to
     `ubuntu-22.04` (jammy) so the PGDG codename matches the OS and isn't
     subject to ubuntu-latest drift.
  2. `pg_dump "$BACKUP_DB_URL" --no-owner --no-privileges | gzip -9` →
     `majstr-db-YYYY-MM-DD-HHMMSS.sql.gz`. `set -euo pipefail` + `test -s`
     guarantee a failed/empty dump fails the job instead of uploading garbage.
  3. Upload to R2 via AWS CLI (`aws s3 cp --endpoint-url $BACKUP_R2_ENDPOINT`),
     under the `daily/` key prefix.
  4. Rotation: `list-objects-v2` with a JMESPath `LastModified < cutoff` filter
     (30 days ago) → `delete-object` each. ISO-8601 sorts lexicographically so
     the string compare is correct.
- **`docs/db-restore.md`** — download from R2 → `gunzip` → `psql -v
  ON_ERROR_STOP=1 -f`, restore-into-fresh-DB vs. disaster-overwrite paths, and
  a verify step (Flyway history + row counts + a `ddl-auto: validate` startup).

## Secrets (GitHub → Settings → Secrets and variables → Actions)

Everything is a secret; nothing sensitive is in the YAML:
`BACKUP_DB_URL`, `BACKUP_R2_ENDPOINT`, `BACKUP_R2_ACCESS_KEY`,
`BACKUP_R2_SECRET_KEY`, `BACKUP_R2_BUCKET` (`majstr-backups`).

## Design notes / gotchas

- **Plain SQL, not custom format.** Matches the `.sql.gz` requirement and keeps
  restore to a single `psql` call (no `pg_restore`). `--no-owner
  --no-privileges` makes restores portable to a Railway DB with a different role
  name.
- **AWS CLI v2 checksums.** Recent CLI versions send request checksums some R2
  setups reject; `AWS_REQUEST_CHECKSUM_CALCULATION=when_required` (+ response
  variant) avoids that across versions.
- **`region auto`**, path/virtual-host handled by the CLI against the R2
  endpoint — same pattern as the app's `S3StorageService` (R2).
- **Fail-loud by design.** No notifications yet — a failed run is visible in the
  Actions tab (red). A Slack/email notify step can be added later.
- **No app code, no migration** — purely ops; the running service is untouched.

## First run (manual test)

GitHub → **Actions** → **Daily DB backup to R2** → **Run workflow** (uses
`workflow_dispatch`). Watch the logs: client install → dump size → upload path →
rotation summary. Then confirm the object exists:
`aws s3 ls s3://majstr-backups/daily/ --endpoint-url $R2_ENDPOINT`.

## Follow-up (tracked)

- **Restore drill not yet performed** — open-questions item. Download the first
  backup and restore into a throwaway DB per `docs/db-restore.md` before relying
  on this for real users.
- Optional: failure notification (Slack/email), and a longer-retention
  weekly/monthly tier under a separate prefix.
