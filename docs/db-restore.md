# Restoring the production database from an R2 backup

Daily backups are produced by [`.github/workflows/db-backup.yml`](../.github/workflows/db-backup.yml):
a plain-SQL `pg_dump`, gzipped, named `majstr-db-YYYY-MM-DD-HHMMSS.sql.gz`,
stored in the Cloudflare R2 bucket `majstr-backups` under the `daily/` prefix,
with 30-day rotation.

**A backup is only as good as a restore you've actually run.** Do a restore
drill at least once (into a throwaway DB) so this procedure is proven, not
theoretical.

## Prerequisites

- `psql` and the AWS CLI v2 installed locally. The client major version should
  be **>= the server** (PostgreSQL 18).
- The same R2 credentials the workflow uses (R2 endpoint, access key, secret,
  bucket). Export them:

```bash
export AWS_ACCESS_KEY_ID=<R2 access key>
export AWS_SECRET_ACCESS_KEY=<R2 secret key>
export AWS_DEFAULT_REGION=auto
export R2_ENDPOINT=https://<accountid>.r2.cloudflarestorage.com
export R2_BUCKET=majstr-backups
```

## 1. Find and download a backup

List what's available (newest last), then copy one down:

```bash
# List daily backups
aws s3 ls "s3://$R2_BUCKET/daily/" --endpoint-url "$R2_ENDPOINT"

# Download a specific one
aws s3 cp "s3://$R2_BUCKET/daily/majstr-db-2026-06-12-030000.sql.gz" . \
  --endpoint-url "$R2_ENDPOINT"
```

## 2. Unpack

```bash
gunzip majstr-db-2026-06-12-030000.sql.gz
# → majstr-db-2026-06-12-030000.sql
```

(You can also restore without unpacking first: `gunzip -c file.sql.gz | psql ...`.)

## 3. Restore into a database

The dump is **plain SQL** (made with `--no-owner --no-privileges`), so restore
with `psql`, not `pg_restore`. Restore into an **empty** database to avoid
"already exists" errors.

**Recommended: restore into a fresh DB first (drill / safe recovery).**

```bash
# TARGET_DB_URL points at a NEW, empty database
export TARGET_DB_URL='postgresql://user:pass@host:5432/majstr_restore'

psql "$TARGET_DB_URL" -v ON_ERROR_STOP=1 -f majstr-db-2026-06-12-030000.sql
# or, streaming from the gzip directly:
# gunzip -c majstr-db-2026-06-12-030000.sql.gz | psql "$TARGET_DB_URL" -v ON_ERROR_STOP=1
```

`-v ON_ERROR_STOP=1` makes psql abort on the first error instead of plowing on.

**Disaster recovery into the live database** (only when you intend to overwrite
it): point `TARGET_DB_URL` at the production DB **after** dropping/recreating its
schema, e.g.:

```bash
psql "$TARGET_DB_URL" -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
psql "$TARGET_DB_URL" -v ON_ERROR_STOP=1 -f majstr-db-2026-06-12-030000.sql
```

> ⚠️ This destroys current data. Take a fresh backup of the live DB first if it
> still responds, and prefer restoring into a new DB and re-pointing the app.

## 4. Verify

```bash
# Migration history present and a few core tables populated
psql "$TARGET_DB_URL" -c 'SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;'
psql "$TARGET_DB_URL" -c 'SELECT count(*) FROM users;'
psql "$TARGET_DB_URL" -c 'SELECT count(*) FROM estimates;'
```

Then point a staging instance of the app at `TARGET_DB_URL` (with
`hibernate.ddl-auto: validate`, which is the default) — a clean startup with no
Flyway/schema-validation errors confirms the restore is structurally sound.

## Notes

- The app expects schema **owned by Flyway** with `ddl-auto: validate`. The dump
  already contains the post-migration schema + `flyway_schema_history`, so a
  restored DB starts at the right version with no migrations to run.
- If you restore into a role whose name differs from production, the
  `--no-owner --no-privileges` dump just creates everything as the connecting
  role — no `ALTER ... OWNER TO` failures.
