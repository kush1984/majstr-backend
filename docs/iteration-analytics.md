# Iteration — funnel by source + first-touch UTM

**Status:** done, unpushed (PWA 1.25.0)
**Source:** `C:\Work\prompts\analytics-prompts-final.md`, prompt 2 of 3 (prompt 1 =
[iteration-analytics-shared-fix.md](iteration-analytics-shared-fix.md); prompt 3 = PostHog, not started)
**Migrations:** `V114__add_utm_first_touch.sql` (the prompt said V110 — the tree was already at V113)

---

## 0. How to read the by-source report (the part worth keeping)

The admin page shows two tables. Both answer the same question — **which channel brings masters who
reach a SIGNED estimate** — and both are easy to misread.

**Compare channels by «% до підпису», and only above the threshold.** Registrations are the cheapest
number on the page and the one a channel can inflate without producing anything. A source that
brings 25 sign-ups of whom 3 sign an estimate is worse than one that brings 8 of whom 4 do, and the
old report (registrations, then "has an object") could not say so.

**The threshold is 5 registrations**, named once in Java
(`MetricsService.SIGNIFICANT_SOURCE_MIN_REGISTRATIONS`) and shipped in the JSON as
`significanceThreshold` so the page keeps no second copy. Below it the row is still shown, under a
«Замало даних» separator, and must not be compared with anything: one registration that signed once
is 100 %, and ranked on that number it would sit on top of the table forever. Five and not ten
because ten, at today's scale, would push every source into that group and the table would read as
broken.

**Every percentage carries its denominator** — `12 % (3/25)`, never a bare `12 %`. At this volume the
percentage is three people, and the fraction is the honest way to say so.

**Which brings the standing caveat:** at the current scale this report is statistically empty and
will stay that way for months. It is infrastructure for the moment volume arrives — data accumulates
from the first day of any media spend — not an instrument for a decision tomorrow. Don't conclude
anything from a source with fewer than a few dozen registrations.

**NULL in the UTM table is an answer, not a gap.** `utm_source` is nullable and NULL means "arrived
with no tags" — today the largest bucket by far. It is rendered explicitly as «без UTM». If that row
ever disappears, the table stops totalling to the registration count and the bug is in the fold
(`HashMap` keeps the null key on purpose), not in the data.

**The two dimensions are not interchangeable.** `referral_source` is a PARTNER (money, rev-share, the
`partners` registry: `LIGA` → Ліга Майстрів). UTM is a CHANNEL (TikTok, Telegram, an article). A
master can follow Ліга's partner link *from* TikTok, so both are stamped and neither is derived from
the other. Merged into one column, both are lost.

---

## 1. What changed

### Backend

| Area | Change |
| --- | --- |
| `SourceBreakdownResponse` | 5 fields → the whole six-step funnel + the two PRO counters, plus `significanceThreshold` and a `utm` list. `activated` keeps its name (it is the object step) — renaming it for cosmetics would break existing readers. |
| `MetricsService.bySource()` | 4 grouped queries → 7 + the shared union; sorted by «% до підпису» among comparable sources; `enoughData` computed server-side. |
| `MetricsService.utmStats()` | Two grouped queries — the channel dimension, both ends of the funnel only. |
| `OwnerSource` (new projection) | `ownerId` + `referralSource` in one row — see §2. |
| `EstimateShareLinkRepository` / `ProjectShareLinkRepository` | `findSharedOwnerIds` → `findSharedOwners` (same rules, now carrying the source). |
| `UserRepository` | `countVerifiedUsersBySource`, `countUsersByUtmSource`. |
| `EstimateRepository` | `countEstimateOwnersBySource`, `countOwnersByStatusAndSource`, `countOwnersByStatusAndUtmSource`. |
| `User` / `RegisterRequest` / `AuthService` | Three nullable UTM columns, stamped once at registration; a blank tag is trimmed to NULL. |
| `V114` | `users.utm_source` / `utm_medium` / `utm_campaign` + an index on `utm_source`. No `DEFAULT`, no backfill. |
| `admin/index.html` | Six step columns + «% до підпису» + a PRO column; a one-time «Замало даних» separator; the UTM table. |

### PWA

`src/lib/referral.ts` gained `captureUtmFromUrl` / `getStoredUtm` under their own storage key,
called from `src/main.tsx` next to the existing `?ref=` capture and spread into the register payload
by `RegisterPage`. Same first-touch law as the ref: written once, never overwritten.

---

## 2. Why the two reports are ONE computation

`shared` is the step that cannot be a `GROUP BY`. It is a union over two link tables reached by two
different paths, and a master commonly holds both kinds of link, so the ids have to be de-duplicated
in Java before anything is counted — which means the by-source split cannot happen in SQL either.

The obvious implementation is a second query for "source of each sharer". That is exactly how the two
reports would drift: one filter added on one side and the aggregate funnel and the breakdown quietly
stop agreeing. Instead `OwnerSource` carries the id AND the source in the same row, and
`sharedOwnerSources()` returns `Map<UUID, String>` — the funnel takes `.size()`, the breakdown groups
the values. One computation, two readings.

**The invariant:** for every step, the sum over all sources equals the matching
`ActivationFunnelResponse` field. `SourceBreakdownIntegrationTest.everyStepSumsToTheAggregateFunnel`
pins it. Note what it can see: two reports DISAGREEING, not both being wrong the same way — which is
why prompt 1 (fixing what `shared` counts at all) had to go first.

Corollary that shaped several queries: **every funnel step filters `role = USER`.** The by-source
rows always did; one demo object on an admin account was enough to break the sum.

---

## 3. Row ordering is owned by the SERVER

The prompt asked for an explicit decision, because doing both ends up with two orderings arguing.

The server sorts and groups (`MetricsService.BY_SIGNED_RATE`: comparable rows first, then «% до
підпису» desc, then registrations, then name); the page renders `rows` in the order it received them
and adds the separator when `enoughData` first turns false. The threshold therefore lives in Java
only and travels in the payload. If ordering ever moves to the client, **delete the comparator**
rather than leave a decorative one behind — the `bySource()` javadoc says so at the point somebody
would find it.

---

## 4. Tests

- `MetricsServiceTest` — the full-funnel row; the union counted once, not summed; the below-threshold
  row flagged and kept off the percentage ranking; the NULL UTM bucket surviving the fold.
- `SourceBreakdownIntegrationTest` (Testcontainers) — the sums-equal-the-funnel invariant across
  masters seeded at every depth; one source carrying the whole funnel with a both-links sharer
  counted once; an admin producing no row at all; the «без UTM» row.
- `UtmFirstTouchMigrationOnLiveDataIntegrationTest` — the "second database migrated to the version
  before the change" drill (V113 → seed → upgrade): an existing master comes out with
  `utm_source IS NULL`, and the partner dimension is untouched.
- `AuthServiceTest` — the tags are stamped at registration and a blank tag lands as NULL (stored as
  `""` it would appear in the report as a nameless channel).
- PWA `referral.test.ts` — capture, no-overwrite, "no tags → store nothing" (storing an empty object
  would claim first touch and lock out the campaign link clicked tomorrow), ref and UTM coexisting.

---

## 5. Not changed / not verified

- **No test harness for `admin/index.html`** and none introduced (vanilla, no build step). The
  threshold behaviour was checked by hand with a throwaway Node script against sample data — not
  committed. What it showed: the separator drawn exactly once, before the first below-threshold row;
  denominators on every percentage (`50 % (4/8)`, `12 % (3/25)`); the «без UTM» row rendered as its
  own labelled row; `hide-mobile` on the four middle steps and on PRO, leaving Джерело / Реєстр. /
  «% до підпису» under 600 px.
- **The admin page was not opened in a browser** — desktop or mobile. The mobile column set was read
  off the CSS rule, not seen rendered.
- **The estimate half of `shared` is still inflated** (the PWA mints the `?t=` link when the share
  sheet OPENS) — a PWA change, deliberately out of scope here, same as in prompt 1.
- **Prompt 3 (PostHog EU, session replay, consent-gated) not started.**
