# Iteration: questions → project messages, with client attachments

> **Retrospective doc.** Written during the 2026-07-27 catch-up from the diffs (`2131473`,
> `caecd29`, plus V75/V76/V77). ~3400 lines across the two commits.

- **Status:** ✅ shipped
- **Migrations:** V74 (project_messages), V75 (share_link_kind), V76 (project_message_files),
  V77 (message_file_deletion_warning)
- **PWA:** shipped alongside (see the PWA commits of the same names)

## The rename is not cosmetic — the old names are gone

| Was | Is |
|---|---|
| `EstimateQuestion` | `ProjectMessage` |
| `QuestionService` | `MessageService` |
| `ProjectQuestionController` | `ProjectMessageController` |
| `/api/projects/{id}/questions` | `/api/projects/{id}/messages` |

`CLAUDE.md` still described the old shape until this catch-up — worth remembering, because that
is the file both agents read before touching anything.

## What actually changed in the model

**A message belongs to the OBJECT, and only optionally to an estimate.** `ProjectMessage.project`
is mandatory, `estimate` is nullable. Previously a question had to hang off an estimate, which
meant there was no way to say something about the job as a whole. A message left from an
estimate still carries it, so the inbox can still show which variant the client meant.

**Direction is unchanged and that is deliberate.** Clients send; the master reads, marks read,
deletes, downloads the files, and follows up through their own channel. There is still no in-app
reply thread. `MessageService`' javadoc says so, and the endpoints match: list / mark-read /
delete / fetch-a-file.

## The new capability: a message link, separate from the portal

V75 adds a **`kind`** to `project_share_links`, so the same table now mints two different things.
That is the whole trick — a master who wants a client to *send* something does not have to
publish an estimate to do it.

- `MessageLinkService` + `MessageLinkController`:
  `GET|DELETE /api/projects/{id}/message-link` (mint / inspect / revoke, owner-only) and public
  `GET|POST /api/public/message-link/{token}`.
- The public POST is **multipart** — this is how a client attaches photos or documents
  (`ProjectMessageFile`, V76).
- `MessageLinkRateLimiter` guards it, same reasoning as the login/register/portal limiters: a
  public write endpoint with no account behind it.

The estimate portal still accepts a message alongside a signature, so both paths in coexist.

## Retention: two passes, on purpose

`MessageFileRetentionService` expires attachments after **six months**, and the two-pass shape is
the point:

1. a pass that **warns** the master which object's file is going and when (V77 stores the warning
   so it is sent once, not every night);
2. a pass that deletes what was ignored anyway.

The reasoning, quoted from the service: *storage a master is paying for is full of files nobody
has looked at since the job ended — but deleting them silently would be data loss dressed up as
housekeeping.*

The grace window is `app.message-files.grace-days` (default 14). `MessageView` reads it so the
app can show a real date; the client should never have to infer the server's schedule.

## Testing

`MessageServiceTest`, `MessageFileServiceTest`, `MessageLinkServiceTest`,
`MessageFileRetentionServiceTest`, plus three integration tests that exist because migrations and
retention are exactly what unit tests cannot see: `ProjectMessagesMigrationIntegrationTest`,
`MessageFilesMigrationIntegrationTest`, `ShareLinkKindMigrationIntegrationTest`,
`MessageFileRetentionIntegrationTest`. `QuestionServiceTest` was deleted with its service.

## Gotchas
- **The naming gotcha survived the rename:** the entity field is `read` (not `isRead`) so the JPQL
  path and derived `...AndReadFalse` queries share one property name, while the view record
  component is `isRead` — that is the JSON key the PWA sees. Don't "align" them.
- Two link kinds now live in one table. A query that forgets to filter on `kind` will happily
  treat a message link as a portal token.
- Attachments are **never** served from the public `/api/files/**`; they go through the
  owner-authenticated message endpoint, same rule as project photos.
