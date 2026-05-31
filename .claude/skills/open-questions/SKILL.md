---
name: open-questions
description: Use at the start of every new iteration, step, or coding chunk before writing any production code. Read docs/open-questions.md, summarize every OPEN and IN_PROGRESS item, and classify each one against the work about to begin (in scope / adjacent / out of scope). Also use when the user signals a new chunk ("next step", "let's start", "продовжуємо", "наступна ітерація", "новий крок", a fresh feature prompt). Also serves as the reminder that every step keeps its own docs/iteration-*.md updated. Skip when the work is a tiny bug fix that touches one file.
---

# Open-questions review

You are about to start (or just started) a new iteration. Walk through
the open-questions log so nothing important is silently skipped.

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

## Per-step docs (do not skip)

Every iteration/step keeps its own file under `docs/iteration-<N>.md` (or
`docs/iteration-fix-a.md` for lettered backend fixes), structured like the
existing ones: status, commit, migrations, goal, the work broken down by
chunk, a "not changed / confirmed" note, and gotchas. Keeping it current is
part of finishing the step, not an afterthought.

- **At the start** (this review): note which iteration doc the upcoming work
  lands in; create the file early if useful.
- **When the step's code is done**: update that iteration doc to match what
  actually shipped, tick the matching boxes in `SPEC.md` (section F), and flip
  any open-questions items the work resolved. A step isn't done until its doc
  reflects reality.
