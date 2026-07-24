---
name: open-questions
description: Use at the start of every new iteration, step, or coding chunk before writing any production code. Read docs/open-questions.md, summarize every OPEN and IN_PROGRESS item, and classify each one against the work about to begin (in scope / adjacent / out of scope). Also use when the user signals a new chunk ("next step", "let's start", "продовжуємо", "наступна ітерація", "новий крок", a fresh feature prompt). Also serves as the reminder that every step keeps its own docs/iteration-*.md updated. Skip when the work is a tiny bug fix that touches one file.
---

# Open-questions review

You are about to start (or just started) a new iteration. Walk through
the open-questions log so nothing important is silently skipped.

## Mobile-first — priority #1 (verify on EVERY change, incl. tiny fixes)

**~95% of masters use the product on a phone.** Mobile is the primary target,
not an afterthought — this applies to every iteration AND every small fix, so it
sits above the "skip for tiny fixes" rule below.

- Any UI/UX change must look and work correctly on a **narrow phone viewport
  first** (≈375px). Design mobile-first, then let it scale up — never the reverse.
- Before finishing UI work, **verify the mobile layout**: use the Browser pane
  with `resize_window` preset `mobile` (375×812), check tap targets, no horizontal
  overflow, readable text, reachable actions (thumb zone), and modals/sheets that
  fit. Prefer bottom sheets / full-width controls over desktop-style dialogs.
- Backend changes that surface in the PWA (new fields, errors, flows) still count:
  confirm the PWA renders them acceptably on mobile.
- If a change can't be mobile-verified in the moment, say so explicitly rather than
  silently assuming desktop is enough.

## Steps

1. **Read** `docs/open-questions.md` in full.

2. **Summarize** every item whose status is `OPEN` or `IN_PROGRESS`.
   Skip `DEFERRED` and `RESOLVED` unless the user explicitly asks for
   them. Group output by the section the item lives in (Architecture &
   operations / Security / Business logic / Features pipeline / Testing
   & quality). For each item show: title, one-line context, and current
   status.

3. **Classify** each `OPEN` item against the iteration the user just
   described (or, if not yet stated, the most recently agreed scope).
   Use exactly three buckets:

   - **In scope** — should be addressed in this iteration
   - **Adjacent** — touches the same files / domain, worth keeping in
     mind while writing, even if not the headline goal
   - **Out of scope** — leave as is

   Be honest: most items will be "Out of scope" on any given step. Don't
   inflate scope to look thorough.

4. **Ask** the user:
   - Do they want to **promote** any `OPEN` item to `IN_PROGRESS` for
     this iteration?
   - Has a new open question come up that should be **added**?
   - Is there anything to **resolve** from earlier work (status →
     `RESOLVED` + one-line summary in place, do not delete the item)?

5. **Apply** the user's answers as inline edits to
   `docs/open-questions.md`. Preserve the existing per-item shape
   (Status / Since / Context / Notes / Resolution). Don't rewrite the
   whole file — surgical edits only.

## Rules

- **Do not invent items.** Only echo what's already in the file, plus
  what the user adds by hand.
- **Status transitions are explicit** — never silently change a status
  without the user's word. If you think something should change, say so
  and wait.
- **Preserve history.** Resolved items stay in the file with their
  resolution line; don't move them to a separate file or delete them.
- **Be concise.** This routine is a lightweight checklist, not a
  re-planning session. If the answer for every item is "out of scope",
  the whole pass should fit in a short message.
- **Tests** — after each fix or change, consider to update the tests or add new ones.
  If the user says "next step" or similar, it's a good time to check if any existing `OPEN` item
  is now `IN_PROGRESS` and should be covered by a test, or if a new test should be added for a
  newly promoted item. Always run tests before pushing, we need to keep the green build.

## Per-step docs (do not skip)

Every iteration/step keeps its own file under `docs/iteration-<N>.md` (or
`docs/iteration-fix-a.md` for lettered backend fixes), structured like the
existing ones: status, commit, migrations, goal, the work broken down by
chunk, a "not changed / confirmed" note, and gotchas. Keeping it current is
part of finishing the step, not an afterthought.

- **At the start** (this review): note which iteration doc the upcoming work
  lands in; create the file early if useful.
- **When the step's code is done**: update that iteration doc to match what
  actually shipped, tick the matching boxes in `C:\Work\SPEC.md` (section F),
  and flip any open-questions items the work resolved. A step isn't done until
  its doc reflects reality.

## Bump the app version every iteration (do not skip)

The user-visible app version lives in `C:\Work\majstr-pwa\package.json`
(`"version"`), surfaced in the Profile "Версія застосунку" row via
`__APP_VERSION__`. Bump it as part of finishing **every** iteration, before the
PWA gate:

- **Big feature** (a new headline capability — a new prompt/step, a new
  entity/migration-backed feature): bump the **minor** — `0.2.0 → 0.3.0`.
- **Small change** (a fix, polish, or follow-up on shipped work): bump the
  **patch** — `0.2.0 → 0.2.1`.
- We're in `0.x`; the agreed trigger (user decision, 2026-07-23) is: **`1.0.0` ships with
  the FIRST PAYING user**. Until then keep bumping `0.x`; when the first real payment
  lands, the next release becomes `1.0.0` (remind the user, don't bump silently).

Do this even for backend-only iterations — the PWA version is the product's
single visible version number, so it tracks the product, not just frontend work.
The backend `build.gradle.kts` version is a separate SNAPSHOT and is left alone
unless the user asks.

## Shared project docs in C:\Work (read every iteration)

Two cross-repo docs live one directory up in `C:\Work` — shared by both
`majstr-backend` and `majstr-pwa`, not inside this repo:

- **`C:\Work\SPEC.md`** — product spec + roadmap (steps, chunks, statuses).
- **`C:\Work\PROMPTS.md`** — running log of the task prompts / definitions.

Read both at the start of an iteration for context, and update the relevant
parts when work lands so they reflect reality (tick SPEC chunks, mark
statuses, note a prompt as done). Surgical edits only — the user owns these
files' overall shape; don't restructure or rewrite them.

## Subagents are pre-authorized

The user has standing approval for spawning subagents (the Agent tool) for
extra analysis or parallel/fan-out exploration when it genuinely helps — no
need to ask each time. Use judgement: handle focused work inline with your own
tools; reach for a subagent when a task sweeps many files/locations or benefits
from an isolated context. Relay only what matters from the agent's result.
