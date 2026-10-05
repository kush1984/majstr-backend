# Review round 2 — §2 (backend should-fix) and §3 (norm data), 2026-10-04

**Status:** built and green on both repos. Migrations **V144** (shared with round 3's §3-§4 batch) and
**V145** (the norm data). Source: `C:\Work\prompts\FIXES-2.md` §2 (B-41…B-54) and §3, continuing
[iteration-money-audit-2.md](iteration-money-audit-2.md) (§0-§1).

With this, **`FIXES.md`, `FIXES-2.md` and `FIXES-3.md` are closed on the BACKEND side** except
**B-76** (`doc_hash` is not reproducible), which is recorded in
[open-questions.md](open-questions.md) with its full analysis rather than half-built. The PWA
sections of rounds 2 and 3 (P-18…P-32, P-36…P-52) are untouched by this pass.

---

## The shape most of §2 turned out to have

Five of these items are the same bug written five ways: **a rule that is right for one case, applied
to every case.** 0,4 kg/m² of grout is the answer for a 10-30 cm tile and was on a 1,6 × 3,2 m slab;
1000 bounded a розгортка in metres and a thickness in millimetres with one number; 2,5 mm was the
joint every grout norm was assumed to be written for, including the one named «від 3 мм»; a primer
row rode every work position as well as the priming step the master prices separately; one
`toLowerCase()` folded an email, a rate-limit key and a storage extension in whatever locale the JVM
booted in.

---

## §2 — backend should-fix

### B-41 — a rename files the line under the trade the master is WORKING in
Fix O gave the three catalog doors `CatalogFiling`; `updateItem` still read the trade off the catalog
row that happened to price the name. `catalog_items` stores a shared position ONCE, under whichever
trade claimed it first, so renaming a line in a PAINTER estimate to «Шпаклювання фінішне» moved it to
DRYWALL — and undid V140's re-filing on the next edit. The line's own current trade is the context
now, and the trade only moves when the shipped library files that exact name+type+unit there. Only
the TRADE is re-derived: the category is the master's explicit pick on the same request.

`tradeIndexKey` also stopped being a third notion of «the same name» (a bare `trim().toLowerCase`,
which disagrees with `NameKeys` about double spaces and the two apostrophes) — it delegates to
`CatalogFiling.key`.

### B-43 + B-80 — one column, two bugs
Both receipt tables carry a client-assigned id, so `save()` could not tell a create from an update
and went through `em.merge`: a concurrent replay of the same queued receipt UPDATED the winner's row,
resetting `expense_id`, the fiscal identity and `billed_on_act_id` to builder defaults, and the
duplicate-key recovery never ran because no key was ever violated. V144's `version` column is a
**`Long`, not a primitive** — Spring Data reads a nullable version as «is this new?», so the insert
is attempted, it collides, and the recovery that re-reads the winner's row finally fires. Written up
with B-80 in [iteration-money-audit-3-crew-and-should-fix.md](iteration-money-audit-3-crew-and-should-fix.md).
A replay loser also no longer copies its photo into the gallery a second time.

### B-44 / B-45 / B-52 — «Мої гроші» reads a period, and a period has bounds
- **An omitted field leaves the stored one** on an object-expense edit. `CashFlowService.update`
  builds that request by hand, so a missing category became OTHER — a MATERIALS cost relabelled
  «Інше» — and a missing date became today, moving the row into a month the master was not looking
  at. Latent while the PWA sends both, and exactly what a replayed offline op walks into.
- **A foreign id is a 404, not a 403.** Letting the object's own service refuse the delete answered
  «forbidden», which confirms the row belongs to someone. An UNKNOWN id stays a no-op, because that
  is what makes a replayed offline delete harmless — the deliberate trade is named in the code.
- **Reversed bounds are refused rather than swapped** (a caller believes something about a period,
  and quietly answering about a different one is how a screen and a strip disagree about one number),
  the range is capped at thirteen months, and both defaults come from ONE clock read — two reads can
  straddle midnight and answer «1 November – 31 October».
- **A category has a direction**: «Аванс» is money in and «Пальне» is money out, and the pair was
  accepted either way round. OTHER belongs to both on purpose, and a null category is not an error.
- **`happenedOn` cannot be in the future** — back-dating is free and deliberate, forward-dating had
  no bound at all. Tomorrow is allowed, because the date comes from a device whose clock is its own.
- V144 adds the two composite indexes the owner-wide cash union scans on.

### B-46 — the contract snapshot now recurses
A component whose type is a record — or a `List<Entry>` of one — rendered as a bare `"array"`, so the
shape the client has to build was outside the contract entirely: `ApplyTemplatesRequest.TemplatePick`,
the catalog batch `Entry`, the import commit items, the measurement rooms and sheets. A required
field added to any of them is the V135 bug one level down and nothing would have gone red. Sixteen
nested records are recorded now, keyed `Parent.Nested`, which keeps the file's shape exactly as it
was so the PWA's reader needs no new vocabulary.

Two of B-46's asks are deliberately NOT done, and the reason is the same: this file is read by a
hand-written parser in the other repo. Recording validation annotations inside the same strings
would break it for a drift class (a bound moving) that costs nobody a 400; and rendering a primitive
`boolean` differently from a nullable `Boolean` has no consumer there — the rule that matters is
already in CLAUDE.md, where B-38 put it.

### B-48 — four file tables, not three, and the add path leaks too
- **A project delete collects `project_message_file` as well.** It was the table B-26 missed and the
  worst one to miss: `MessageFileRetentionService` finds files THROUGH their rows, so a key orphaned
  here could never be cleaned by anything afterwards — and a client's attachment is his own data,
  which a deletion is supposed to remove.
- **An act delete collects its receipts' keys.** Each is a photographed slip, which is financial
  personal data, and they outlived the act with nothing pointing at them.
- **Any failure after a `storage.store` drops the blob.** The won-race branch already did (there the
  file is redundant rather than orphaned); every other exception did not, in both receipt services
  and in both photo paths.

### B-49 — the calculator's own «right for one case»
- **A bound per question**: thickness ≤ 150 mm, section ≤ 5 m, perimeter ≤ 1000 m. They shared a
  single 1000, which bounded neither — a screed typed as 400 instead of 40 passed and put 800 kg/m²
  of dry mix on the shopping list, with the arithmetic line reading as if we meant it.
- **The separator is `;` or a comma that starts a new id.** This parameter's own documented shape is
  «uuid:0,4,uuid:0,55» — a comma DECIMAL, which a Ukrainian keyboard types — and splitting on a bare
  comma cut it in half: «uuid:0» parsed as zero (read as «unanswered») and «4» had no id. The answer
  vanished in silence and the card went on asking. Every dot-decimal request keeps working.
- **Grout scales against the joint ITS OWN norm was written for** — V145's `baseline_param`. «Затирання
  швів від 3 мм» carries 0,8 kg/m² because its joint is wider, and rescaling that against 2,5 took a
  master's 5 mm habit and doubled the one figure whose data was most specific.
- **`ENAMEL_WOOD` and `VARNISH_CLEAR` are deliberately NOT rescaled by the paint habit**, and that is
  now a decision rather than an accident of prefix matching: `PAINT_COVERAGE` is the master's answer
  about HIS WALL PAINT, and V138 wrote 0,22 and 0,20 л/м² for those two at ONE coat — a two-coat
  wall-paint ratio would be wrong in both factors at once.
- The facade comment said «6,5 м²/л»; V138's own derivation is 0,35 л/м² over two coats, ≈5,7.

### B-50 — which self-checks may fail a deploy
Nothing to change in an applied migration (Flyway checksums it), so this became a rule in CLAUDE.md:
**a `RAISE EXCEPTION` may only be about the migration's OWN work.** «I just re-filed these rows,
assert none is left» is a bug if it fails; «every shipped norm still finds a catalog position» is
about data an admin can rename between two deploys, and that is a `RAISE WARNING` with the
integration tests keeping it honest in CI. V145 follows it throughout.

### B-53 — the twin warning, and the identity that arrives last
- **Once an act has billed a receipt, the duplicate warning goes silent.** The reconciler has already
  answered the question it asks, and «схоже, це той самий чек» over an answered question reads as a
  problem the master has to go and fix. `billedOnActId` + `billedOnActNumber` say «врахований в акті
  № N» instead.
- **A receipt that learns its identity AFTER the act was signed settles against it.** That is the
  ordinary order of events — the act is built and signed on site, the till receipts are photographed
  in a batch and their QRs read later — and by then the sign-time reconciler had matched nothing, so
  the paper sat in the «клієнт відшкодовує» receivable while the act's own copy had moved the same
  money into «За договором». `ActReceiptReconciler.settleAgainstSignedActs` closes it from the
  receipt's side, with the same two halves and the same conditions as the sign-time pass.
- V136's header justified not unwinding a mis-reconciled receipt with a `doc_hash` argument that is
  wrong (neither `billed_on_act_id` nor the object's expense is in the hashed render). V136 is
  applied, so the correction is in **V144's header** instead — and the RULE it stated is right for a
  different reason, which that comment now gives.

### B-54 — the small things
`Locale.ROOT` on every fold of a KEY (emails, the login bucket, a material habit, a storage
extension, the admin period) — a Turkish-locale JVM folds «I» to «ı» and a login silently matches
nothing. A fiscal lookup that RAN and did not answer is now `error.fiscal-qr.lookup-unavailable`
rather than «позицій у чеку немає», which blamed the paper for the tax service's outage: the reason
travels on the receipt itself (`FiscalReceipt.PositionSource`), so all three cases point at a
different next move. `BucketRegistry` gained a 100 000-key cap beside the idle sweep — six of the
eleven limiters are keyed by something a stranger picks, and between two sweeps a key-rotating caller
could add entries at request rate. `PortalTouchTargetsTest` **strips comments before matching** (a
rule surviving only inside a `/* … */` kept it green while every control had lost it) and left the
`service` package, where it had nothing to do with a service. The admin activity tint uses the same
strict comparison the ordering query does.

The markup's silent skip of a «%» line is left as it is and said so in the code: the editor's own
`canPickForMarkup` offers only WORK lines with a real unit, so the picker cannot produce the case —
reporting it in the response would document something only a hand-written request can do, and it
would not reach the master offline, where that op is queued.

---

## §3 + B-51 — the norm data (V145)

**Primer is bought once** (B-51, the owner's option (a)): laying, painting, putty, waterproofing and
self-levelling positions each carried their own primer row, and the shipped bundles ALSO contain a
standalone «Грунтування» step — «Санвузол під ключ» bought primer on five lines. The rule V145
applies is **data-driven, not a list of names**: an m² position keeps its primer row only when primer
is the ONLY thing it consumes, which is exactly «only on the standalone position» and needs no
maintenance when a later migration adds another work position.

The bound on that rule is the ruling itself: the standalone step is priced per SQUARE METRE, so it
can only stand in for work measured the same way. A hidden-mount door priced per LEAF — filled flush
and painted with the wall's own paint (V139) — keeps its primer, because no m² of priming the master
also prices covers it. §3's moulding ruling is separate and has a PRODUCT reason: nobody primes a
foam or PU baguette, which is why V139 already left primer off an ordinary door.

The rest of §3, each row with the sheet it came from:

| What | Was | Now | Why |
|---|---|---|---|
| Мозаїка, 4 positions | C2 3,9 | **2,6** | a 3-4 mm notch: Kreisel 1,95-2,6, CM 117 Pro 1,9, CM 11/117 at 4 mm 2,5-2,6 |
| 300×900, дошка до 900/1200 | C1 7,8 / 6,5 | **C2 8,5** | no C1 sheet allows the format; «до 900» also broke V137's own 12 mm rule |
| керамічний паркет | C1 6,5 | **7,8** | the same 12 mm notch rule |
| шар клею > 1 см | C1 | **C2** + a grout row | C1 sheets cap the layer at 10 mm, and a thick bed is grouted too |
| 600-800 mm formats | grout 0,4 | **0,15** | CE 33/40 is geometric — fewer metres of joint per m² |
| ≥1000 mm, керамограніт | grout 0,4 | **0,1** | same table, further along it |
| клінкер «під цеглу» | grout 0,4 | **1,2**, baseline 5 mm | CE 43: 10×10 at 5 mm → 1,2; a brick slip at 10 mm is ≈3 by geometry |
| клінкерна підлогова | grout 0,4 | **0,8**, baseline 10 mm | CE 43: 30×30 at 10 mm |
| шпаклювання швів гкл … зі шліфуванням | PUTTY_JOINT 0,4 | **0,3** | Uniflott, Siniat, Rigips 0,25-0,3 per m² of board |
| герметик / акрилення, 4 positions | ACOUSTIC_SEALANT | **SEALANT_SILICONE / SEALANT_ACRYLIC** | the acoustic tube is a drywall product; the rate is the same, the TUBE is not (0,3 l cartridge, not 0,6) |
| шпалери паперові / фотошпалери | glue 0,01 | **0,2 kg/m²** | 0,01 was Quelyd's fleece figure; Capacoll gives 0,15-0,3 dispersive |
| PRIMER_CONTACT, PRIMER_QUARTZ | 15 kg | **7,5 kg** | CT 19 / CT 16 sell 7,5, and V137's own rule is that the smaller package wins |
| PRIMER_DEEP | 10 l | **5 l** | it is now on the standalone step, where 10 l is over half a job |

Every statement covers the masters' own FORKS as well, which is the B-35 rule; where a shipped row is
DELETED its forks go with it, because a fork hides a default and a fork with no default is a
coefficient still being applied for a reason nobody can read.

**The test that was asked for, and the one that was already there.** §3's last bullet named four
drywall figures nothing read (the sloped ceiling, both partition builds, the two-layer wall) and a box
check written as `NOT IN (20, 28)` — which passes just as happily with the two values swapped, and a
radius box at 20 beside a straight one at 28 is a real mistake. Both are now per-position assertions,
and V145's own corrections are read back from the live database by
`NormDataCorrectionsOnLiveDataIntegrationTest`.

**Still refused, with the ranges recorded in V138's header:** epoxy grout (Ceresit CE 79's own table
spans 0,08-12,40 kg/m²) and the decorative plasters (a threefold spread inside one product name).
Those are a range the master picks from, not a norm.
