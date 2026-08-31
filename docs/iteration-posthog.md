# Iteration — PostHog: product analytics + session replay (PWA only)

**Status:** done, unpushed (PWA 1.29.0)
**Source:** `C:\Work\prompts\analytics-prompts-final.md`, prompt 3 of 3 (prompt 1 =
[iteration-analytics-shared-fix.md](iteration-analytics-shared-fix.md), prompt 2 =
[iteration-analytics.md](iteration-analytics.md))
**Migrations:** none — **no backend change at all**, not one line

---

## 0. Why, in one paragraph

The admin funnel added by prompts 1-2 is **state-shaped**: it counts how many masters ever reached
state X. It knows nothing about time, cohorts, or what happened BETWEEN two steps — so it can say
«30 masters built an estimate and 12 shared it» and never say why the other 18 stopped. PostHog is
the **event-shaped** counterpart, and the reason it is here is **session replay**, not dashboards:
watching a master build an estimate and put the phone down is worth more than any funnel chart at
this scale.

The two are complements, not duplicates, and the boundary is a rule, not a preference:

> **Money and state belong to the backend. PostHog only gets what the backend does not already
> write.**

That rule alone removed two of the events the prompt suggested — see §3.

---

## 1. Off by default, and off means the SDK never downloads

`src/lib/posthog.ts` mirrors `src/lib/sentry.ts`: the key lives in `src/lib/config.ts`
(`VITE_POSTHOG_KEY`), init is called once from `main.tsx`, and with an empty key — the dev default,
and the value in `.env.example` — **nothing happens**.

It is stronger than Sentry's «we don't call init»: the SDK is behind a **dynamic
`import('posthog-js')`**, so with no key the module is never fetched, never parsed, never evaluated.
Verified against a real build, not assumed:

| build | posthog chunk | main chunk |
|---|---|---|
| no key (the committed default) | **absent entirely** — Vite folds `config.posthogKey` to `''`, the branch is dead code and Rollup drops the import | 1 590.30 kB |
| `VITE_POSTHOG_KEY=phc_probe` | a separate lazy chunk, **269.48 kB / 88.58 kB gzip** | 1 591.24 kB (+0.94 kB) |

So a key costs ~1 kB on the critical path and everything else arrives after boot, off the main
thread of the first paint. Region is **EU (Frankfurt)** — `VITE_POSTHOG_HOST` defaults to
`https://eu.i.posthog.com`; the data is Ukrainian masters' and there is no reason for it to sit in
the US.

`VITE_POSTHOG_REPLAY_SAMPLE_RATE` exists but defaults to **1** — record everything. The free tier
allows 5 000 recordings a month, which this scale cannot approach, and replay is the whole point of
the integration; sampling would save a resource we are not short of at the cost of the thing we came
for. `replaySampleRate()` in `config.ts` falls back to 1 on anything missing or unparseable, so a
typo in the env can only ever mean «record everything», never «silently record nothing».

---

## 2. Privacy is the design, not a footnote

Two promises, both now written into the `/privacy` page, both pinned by tests in
`src/lib/posthog.test.ts`.

### 2.1 Nothing is captured before consent

The SDK boots with `opt_out_capturing_by_default: true`. Capturing starts only when
`applyAnalyticsIdentity(me)` sees a stamped `me.consentedToPrivacyAt`.

**One function, not two call sites gating themselves.** `applyAnalyticsIdentity` is the single door:
it reads the consent stamp, opts in or out accordingly, and identifies **only** a consented master.
A caller that gated on consent but forgot to identify would lose the segmentation; one that
identified without the gate would be a privacy bug. Callers pass `me` and know nothing else. It is
called from `AppLayout` (boot / re-open, keyed on the whole `me` — consent and plan change without
the id changing), `useLogin` and `useRegister`.

Deliberately **not** «init only after consent»: booting the SDK opted out means the moment consent
is stamped, capturing starts without a reload — and a pre-consent session still sends nothing.

An anonymous visitor — including a client who opened `/privacy` from a portal link — never opts in,
so they are never recorded. The client portal itself is `static/portal/index.html`, a different page
that does not load this bundle at all; it was not touched and must not be.

### 2.2 No personal data leaves the device

- **`autocapture: false`.** This is the one that matters most. Autocapture ships the *text* of
  whatever was clicked — that is exactly how a client's name or an estimate total ends up inside an
  event name, in a system where nobody ever reads the raw events. `capture_pageview` /
  `capture_pageleave` are off too; the event list is closed (§3).
- **`person_profiles: 'identified_only'`** — an anonymous visitor never becomes a row.
- **Identity is the UUID.** `identify(user.id, …)` with a fixed, reviewed property set:
  `plan`, `email_verified`, `trades`, `referral_source`, `utm_source`. No email, no name, no phone,
  no company. A test serializes the properties and asserts the fixture's email, full name, phone and
  company name appear in none of them.
- **A master-invented trade never travels.** Custom trades are FREE TEXT — «Ремонти від Петра К.» is
  a perfectly ordinary one and can carry a surname or a company. `personProperties` folds the *fact*
  that the master has one into the system `OTHER` it already sits under (V91) and drops the name.
- **`referral_source` / `utm_source` come from the device's own first-touch storage**
  (`getStoredRef` / `getStoredUtm`), because `/me` exposes neither. For a master who registered on
  another device they are simply absent — an absent property beats a wrong one.
- **Replay masking**: `maskAllInputs: true` plus **one** class, `.ph-mask`, hung on whole sensitive
  *containers* — the profile hero and edit form, the client picker, the client edit form, the
  client card on the object screen, and the entire object-economy section. A per-selector list is a
  list somebody forgets to extend when the next screen ships; a container class travels with the
  container.

> **Trap, and the reason a test asserts the nesting rather than the values:** the masking options
> live **inside** the `session_recording` object. Put at the top level they are silently ignored —
> `posthog.init` takes unknown keys without complaint — and the only way to find out is to watch
> your own recording. `posthog.test.ts` asserts `session_recording.maskAllInputs === true` **and**
> `expect(opts).not.toHaveProperty('maskAllInputs')`.

### 2.3 The privacy policy had to change in two places, not one

Adding a PostHog `<li>` to `/privacy` (EU servers, what is masked, "recording starts only after
your consent", "the public client portal is not recorded at all") was the obvious half. The other
half was a **correction**: the existing "Технічні дані" line promised anonymized usage statistics
were «не для реклами й не передається стороннім» — true until this iteration, false the moment a
third-party analytics service processes them. It now says the statistics and the session recordings
are processed by the analytics service named in §5 and start only after consent.

The same edit finally names the **registration source** (partner link or ad channel, anonymized,
for partner accounting) — an open question since the referral-attribution iteration that had been
"fold it into the lawyer-review pass" while `referral_source` never left our own database. It leaves
the database now, as a person property, so it stopped being optional. That open-questions item is
RESOLVED; the lawyer-review pass is still its own OPEN item.

### 2.4 `reset()` on logout is mandatory, not hygiene

`useLogout` calls `resetAnalytics()` beside `setSentryUser(null)`. Without it the next person to log
in on that device is appended to the previous master's person, and their session recording is filed
under them. **A crew shares one phone** — this is not a theoretical case here.

---

## 3. The event list is closed, and two suggested events were dropped

`EventMap` in `posthog.ts` is a typed map, not a free `capture(name, props)`. «Just in case» events
are how an analytics layer turns into noise nobody trusts, and a typo in an event name is invisible
until someone builds a funnel on it.

| event | where it fires | properties |
|---|---|---|
| `registered` | `useRegister.onSuccess` | `source`, `utm_source` (first touch, from this device) |
| `email_verified` | `useVerifyEmail.onSuccess` | — |
| `project_created` | `useCreateProject`, in `optimistic` | `hasClient` |
| `estimate_created` | `useCreateEstimate` + **both halves** of `useApplyTemplate` | `itemCount`, `fromTemplate` |
| `estimate_shared` | `SharePortalSheet`, on a real copy/send | `scope` (`estimate`\|`object`), `channel` |
| `act_created` | `ActEditorPage.persist()`, create branch | — |
| `act_shared` | `ActShareSheet`, on a real copy/send | `channel` |
| `act_signed` | `ActEditorPage.onSign` | `mode: 'offline'` |

**`checkout_started` — dropped.** The backend already persists a PENDING `Payment` row (period, kind
`CHECKOUT`, wallet id when auto-renew is intended) on **every** `POST /api/billing/checkout`, before
the redirect, and `UpgradeEventService` already writes CLICK/INTEREST. A second, independent count
of the same act would drift from the money within a month. This is the «money belongs to the
backend» rule doing its job.

**`estimate_signed` — dropped.** The master's app has no signing path at all. An estimate is signed
by the CLIENT in `static/portal/index.html` — a separate page and a person who never consented to
being measured. `withSigned` in the admin funnel is the honest source for that number.

**`act_signed` — kept, but narrowed to `{ mode: 'offline' }`.** `signOffline` is the one signature a
master performs in their own browser; the portal act signature happens in the client's. The property
exists so the event can never be read as «acts signed».

**No `isFirst` flag** on `project_created` / `estimate_created`, though the prompt suggested one:
PostHog already knows a person's FIRST occurrence of an event, and a flag computed off the local
cache would be wrong after a reinstall and unknowable offline. `hasClient` replaced it — objects
created with no client attached are the ones that never reach a share.

### 3.1 Two placement rules that are not obvious

**Sharing is counted where the link LEAVES the app, never where the sheet opens.** Both sheets mint
or publish a link on open (`SharePortalSheet` mints the single-estimate `?t=` link in an effect;
`ActShareSheet` publishes DRAFT→SENT). Counting the open would repeat in PostHog exactly the lie the
backend funnel's estimate half still tells — «опублікував портал АБО просто відкрив шторку» (see
[iteration-analytics-shared-fix.md](iteration-analytics-shared-fix.md)). So the capture sits inside
the success branch, after the clipboard actually accepted the text or the email actually went.

**Creation is counted where the object exists FOR THE MASTER — the optimistic branch.** An object
authored in a basement is an object; counting only the `online` branch would make offline work look
like idleness, which is the opposite of what this product claims. `useApplyTemplate` therefore fires
in **both** halves — the server-composed one and the device-composed one.

---

## 4. What was deliberately NOT done

- **No service-worker exception.** `src/sw.ts` handles navigations and precached same-origin assets;
  `vite.config.ts` declares no `runtimeCaching`, so a cross-origin PostHog request is never
  intercepted. Nothing needed adding, and nothing was added «just in case» — an allow-list entry for
  a third-party host is precisely the kind of line that later turns into an offline bug.
- **No backend change.** Not an endpoint, not a column, not a config key.
- **Nothing in a working flow depends on this.** Every entry point is a no-op when disabled, the
  dynamic import `.catch(() => null)`s, and `withClient` swallows anything the SDK throws — the same
  fail-soft shape as web push. A test asserts `track` / `applyAnalyticsIdentity` / `resetAnalytics`
  do not throw with analytics off.
- **Honest about offline:** analytics never *blocks* on the network, and the capture call itself is
  synchronous-and-queued, but posthog-js does buffer in `localStorage` and retry, so a failed beacon
  can be attempted while offline. It is invisible to the master and to every flow; claiming «zero
  network activity offline» would be the stronger, false statement.
- **Sentry replay stays off** — `sentry.ts` now says so in a comment at the option it would go in.
  Two recorders would double the cost and record the same screens twice, and PostHog is the one
  carrying the masking rules the privacy policy names.

---

## 5. Tests

`src/lib/posthog.test.ts` (11 tests) — the module keeps loaded-SDK state, so each test re-imports it
with `vi.resetModules()` + `vi.stubEnv`, which exercises the real `config.ts` env parsing too:

- disabled: `init` never called with an empty key; `track` / `applyAnalyticsIdentity` /
  `resetAnalytics` neither throw nor capture.
- init options: opted out by default, `autocapture: false`, `capture_pageview: false`, and the
  masking nested inside `session_recording` and **not** at the top level; init happens once.
- consent gate: an unconsented master is opted out and NOT identified; a consented one opts in and
  is identified by UUID with no email / name / phone / company anywhere in the properties.
- person properties: a custom trade folds to `OTHER` and its free text never appears; first-touch
  tags travel when the device stored them and are absent when it did not.
- logout: `reset()` is called.
- events: name and properties are sent verbatim; a propertyless event carries none.

Full PWA gate green: `lint` · `tsc -b` · `typecheck:tests` · **741 vitest** (105 files) ·
`vite build`.

---

## 6. Follow-ups

- The PostHog project must exist in the **EU** region and its key be set in the deploy env before
  anything is collected. Until `VITE_POSTHOG_KEY` is set in production this iteration is inert by
  construction — which is the intended shipping state, since the privacy policy naming PostHog must
  be live first.
- **The estimate half of `shared` is still inflated on the backend** (the PWA mints the `?t=` link
  when the sheet OPENS). The PostHog event does not have this problem; the backend funnel still
  does. Fixing it is a PWA change to `SharePortalSheet`'s mint-on-open effect, still out of scope.

---

## 7. Going live found two things, and neither was in the code under test

Recorded because both cost a round trip to diagnose and both look identical from the cabinet:
an empty project, "no events yet".

**(a) `VITE_*` is baked at BUILD time, so it belongs to the PWA host, not the API host.** The key
was first added to the backend service on Railway, which never reads it — the PWA is a separate
deploy on Cloudflare Pages (`majstr.pro` answers `Server: cloudflare`, `api.majstr.pro` answers
`Server: railway-hikari`). The proof is in the shipped bundle: fetch
`https://majstr.pro/assets/index-*.js` and grep it. `posthogKey:""` means the build had no key;
`posthogKey:"phc_…"` means it did. Two consequences worth keeping: **setting the variable is not
enough — the existing build must be redeployed**, since nothing reads it at runtime; and the
bundle grep is the fastest honest answer to "is it on in prod?", ahead of any dashboard.

**(b) `Number("") === 0`, and 0 is a legal sample rate.** `replaySampleRate` fell back to 1 for a
missing or unparseable value but accepted a blank string as a deliberate "record nothing" — and a
blank string is exactly what a hosting dashboard produces when someone creates the variable and
leaves the value empty (which had already happened on the wrong service, one copy-paste away from
travelling to the right one). The failure mode is the nasty kind: the SDK downloads, events flow,
the config looks set, and session replay — the entire reason PostHog is here — is silently off.
Fixed with a blank check *before* `Number` (`config.ts`), pinned by `src/lib/config.test.ts`,
which keeps an explicit `"0"` working: turning replay off on purpose must stay possible.

The general rule this leaves behind: **an env var that is set-but-empty must mean "unset", not
"zero"** — anywhere `Number()` reads one. `apiBaseUrl` is the deliberate exception and says so in
its own comment (an explicit empty base URL is a real, used configuration).

PWA 1.29.1 → **1.29.2**. Full gate green: `lint` · `tsc -b` · `typecheck:tests` · **757 vitest**
(107 files) · `vite build`.

## 8. The replay → real person join (backend, 2026-08-31, no migration)

The master, after the first recordings landed: «як можу знати для якого конкретного користувача
відео стосується, щоб можливо йому зателефонувати і проговорити ситуацію де він застряг?»

That question is the whole privacy design working as intended, and then hitting a missing step.
PostHog is deliberately told **nothing but the UUID** — no name, email or phone (§2) — so a
recording identifies its master by `distinct_id` and that is `users.id`. The name and phone live
where they belong, in our own admin: `AdminUserDetail` already carries `fullName`/`phone`/
`companyName` under a comment that says «who this actually is, and how to reach them».

**The missing step was that the admin panel could not be handed an id.** `searchAdminByPattern`
matched `email`, `fullName` and `companyName` and nothing else, so pasting a UUID returned zero
rows. `GET /api/admin/users/{id}` would have answered, but it is an API call with a bearer token,
not something you do from the page you are already looking at.

So the search term is now read **two ways at once**: `likePattern(search)` as before, plus
`idOrNull(search)` — the term parsed as a UUID, or null — ORed into the same WHERE as `u.id = :id`
in both ordering variants. Three deliberate details:

- **An id is matched by EQUALITY, never LIKE.** A UUID is not a substring anyone types, and
  `%…%` over a text cast of the primary key is a sequential scan for no benefit.
- **`UUID.fromString` is lenient below 36 characters** — it stops validating and just splits on
  `-`, parsing each group as hex. So `866feca8-dc5b-403f-993e-c6` (a half-copied id, and PostHog
  shows exactly that shortened form in its list) parses into a valid but **different** uuid, and a
  bad paste would have silently searched for somebody else. `idOrNull` therefore requires the
  canonical 36-character form before it parses at all. This is the one thing here that could have
  shipped looking correct; it was caught by the test asserting a truncated id is rejected.
- **A non-id term still searches text, and an id term still travels as a pattern too.** The
  branches are ORed, never exclusive.

**What was deliberately NOT done: sending name or phone to PostHog as person properties.** It is
the obvious one-line "fix" and it is exactly the boundary this iteration exists to hold — the
privacy policy says what leaves the device is depersonalised. The UUID as a join key, resolved in
our own admin, answers the same question without moving personal data to a third party.

Tests: `UserRepositorySearchTest` (id parsing incl. the truncation trap, and the forwarding of both
branches) plus a case in `AdminUserSearchOrderingIntegrationTest` — the latter against a real
database on purpose, because whether `OR u.id = :id` executes, and whether binding a **null** UUID
into it leaves plain text search working, is a question only PostgreSQL answers. Admin search has
already 500'd once on exactly that class of bug (a bind parameter Postgres could not type), and it
was green in every mock. Backend green: `./gradlew build`, **1128** tests.
