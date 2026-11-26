# Regional prices — plan (steps 1–2 built)

**Status:** step 1 shipped 2026-11-24 (V222 + V223, backend `abf54eb`, PWA 1.89.0 `d89d38b`); the price hint (part of step 4) and step 2 «Ціни по областях» shipped 2026-11-25 (PWA 1.92.0, 1.93.0); steps 3 and the rest of 4 not started. Builds on V219 (`users.region`,
[iteration-regions.md](iteration-regions.md)) and the community prices of V94 (`PriceInsightService`).
Open-questions entry: «Community prices: one national median ignores regional cost differences».

## What the owner wants

1. **A new master gets his oblast's prices.** «Якщо реєструється майстер з Львівської області, то йому в
   каталог мають іти Львівські ціни.»
2. **A master can look at another oblast's prices.** A master's request: when he travels to work in
   another oblast he wants to see what the works he cares about cost there. The owner: «це прям капець
   як важливо для людей».

## Where we are today

- A new master's catalog is copied from ONE national default price list (owner_id NULL rows, per trade
  since V220) — the same figure in every oblast.
- Community prices (V94): a weekly two-level median of masters' estimate lines, a position counts with
  `PriceInsightMath.MIN_MASTERS` = 3 masters after the trim. One median for the whole country; it only
  proposes changes to the default (admin apply → `catalog_update_notices`).
- `users.region` (V219): 24 oblasts + Kyiv city, NULL = not answered. Asked on the home screen AFTER the
  first real object — i.e. after the catalog was already seeded. Nothing reads it.

## The real problem: thin data

A per-position median per oblast needs 3+ masters with that position in that oblast. Kyiv and Lviv
will get there; most oblasts will not for most positions for a long time. So prices resolve in three
levels, most precise first:

1. **Position × oblast median** — 3+ masters in the oblast have the position (after the trim).
2. **Oblast index × national price** — one coefficient per oblast (later maybe per oblast × trade):
   the ratio of the oblast's masters' prices to the national median, over every position both sides
   have. Needs a few masters in the whole oblast, not per position. Shown as «орієнтовно».
3. **National price** — the oblast has too little data even for an index; said plainly.

No coefficient typed by hand from internet price lists: that is guessing, and masters see through it.
The index is computed from our own masters' data only.

Every regional figure carries its level, the number of masters behind it and the date of the run.

## How it looks to a master

### 1. A new master's catalog in his oblast's prices

- Ask the oblast on the «Чим займаєтесь?» screen at registration, BEFORE the catalog is seeded (today
  it is asked too late for that). Optional, like the trades.
- Seeding resolves each copied position through levels 1 → 2 → 3.
- A master who answers later (existing accounts, «Пізніше»): his catalog is never rewritten silently.
  A notice instead — «У Львівській області ціни інші — оновити N позицій?» — through the same
  `catalog_update_notices` flow V94 uses. Only positions he never edited himself; his own prices are
  never overwritten.

### 2. «Ціни по областях» — another oblast's prices

- Catalog → «Ціни по областях»: pick an oblast; the same trades and categories as his catalog; for each
  work three figures — his own, that oblast's (e.g. «320 ₴ · 7 майстрів», or «орієнтовно»), the
  national one. Search by work name.
- Read-only; nothing is written to his catalog unless he taps to take a price (later, if asked).
- Later step: an object's own oblast (a Lviv master working in Kyiv), and the estimate's add-position
  hint shows that oblast's prices.

### 3. Privacy

- Only medians are shown — never one master's price, name or town.
- For ANOTHER oblast the threshold rises to 5 masters per position: in a small oblast with three
  masters, each could otherwise work out his neighbours' prices. The own oblast may stay at 3
  (owner's call, below).

## Order of work

1. **Foundation (no visible change for masters).**
   - The oblast asked at registration (trades screen).
   - A weekly job writes medians per position × oblast and the index per oblast into their own table
     (trade, name, unit, region, median, masters count, level, run date); the national median stays.
   - Admin: masters per oblast, and how many positions have level-1 data in each.
   - This step shows whether there is enough data for step 2 at all.
2. **«Ціни по областях» viewer** — the master's request.
3. **Regional seeding** for new masters, and the «оновити N позицій» notice for those who answer later.
4. **The object's own oblast** and regional hints while adding a position to an estimate.

## Step 1 as built (2026-11-24)

- **V222** `regional_price` (region, name key, sample name, type, unit, median, masters, min, max,
  run time; CHECK masters ≥ 3) and `regional_index` (region → factor, masters, positions, run time).
  **V223** `regional_baseline` (name key, sample name, type, unit, median, oblasts, run time; CHECK
  oblasts ≥ 3) — the «country» the factor is measured against (see below for why it is not V94's).
  No trade column: estimate lines carry none, the key is the national one (fuzzy name, type, unit).
- **`RegionalPriceService`** — weekly in `PriceInsightRefreshJob` after the national run, in its own
  transaction (`REQUIRES_NEW`); a failure is reported to Sentry and leaves the national candidates
  alone. All three tables are deleted and rewritten on every run — nothing accumulates.
- **Admin «🗺 Ціни по областях»** (`GET /api/admin/regional-prices`, `POST …/refresh`) — every oblast
  with the masters who named it, positions with an oblast median, the factor (and how many masters /
  positions it rests on); masters without an oblast; positions with a country baseline; the run time.
  «Перерахувати зараз» instead of waiting for Saturday.
- **PWA 1.89.0** — «У якій області працюєте?» on «Чим займаєтесь?» (optional), before the catalog.
- Tests: `RegionalPriceIntegrationTest` (3 masters make an oblast median, 2 do not; the baseline is one
  vote per oblast and ignores a master without one; factor 1.167 from 111/117/122 %; two oblasts are
  not a country; a rerun replaces rows), `PriceInsightRefreshJobTest`, `TradesOnboardingPage.test.tsx`.

## Price hint as built (2026-11-25)

The part of step 4 a master asked for first («коли я ставлю ціну… щоб підказувало ± ціну по ринку»),
FREE for everyone (owner). `GET /api/catalog/price-hint` → `PriceHintService`: his oblast's median
(`OBLAST`, «Ринок, Львівська обл.: ~350 ₴ за м² · майстрів: 7») → baseline × his factor
(`OBLAST_ESTIMATE`, «Орієнтовно…») → the library price (`COUNTRY`, «По країні…»). Name matched by
`CatalogNameKey` only; works only; 204 = no line. PWA `PriceHint` under the price field of an estimate
line and of a catalog position, with «Порівняти ↗» opening a web search of rabotniki.ua (option 1 of
«Market prices from other sources»). Owner decisions the same day: threshold 3 masters for any oblast;
the «Ціни по областях» viewer of OTHER oblasts is PRO and not built yet.

## How it is computed (keep this in sync with `RegionalPriceService`)

Owner, 2026-11-24: «все ретельно документуй, щоб ми памятали як все рахується». Every Saturday 02:00
Kyiv (`app.price-insight.refresh-cron`), right after the national community run, or on «Перерахувати
зараз» in the admin.

**0. Input — one number per master per position.** `EstimateItemRepository.aggregatePerMasterWorkPrices`
(the same query the national V94 run reads, so the two never disagree about a master's price):

- lines of estimates that are not REJECTED, on objects that are not the training sample;
- `type = WORK` only, not `PERCENT`, `unit_price > 0` (materials, percentages and unpriced drafts are
  not quotes);
- grouped per master × exact lowercased name × type × unit → that master's MEDIAN `unit_price`
  (Postgres `percentile_cont(0.5)`), plus his `users.region` (NULL = not said).

In Java the exact names fold by `CatalogNameKey` (the fuzzy key the catalog uses) — a position is
(fuzzy name, type, unit). A master with several spellings of one position becomes one number: the
plain median of his spellings. So each master has **at most one number per position**, however many
estimates he wrote.

**1. Oblast median (`regional_price`).** For each position and each oblast: that oblast's masters'
numbers → the IQR trim (`PriceInsightMath.trimmedMedian`: drop values outside Q1 − 1.5·IQR … Q3 +
1.5·IQR) → if 3+ masters remain, their median (2 decimals), with min/max of the survivors. Fewer than
3 → no row; the oblast simply has no figure for that work.

**2. Country baseline (`regional_baseline`).** For each position: every oblast that has at least one
master pricing it gets ONE vote — the plain median of its masters' numbers (no trim, any count, even
one master). If 3+ oblasts voted, the baseline is the plain median of those votes (2 decimals).
Masters without an oblast take no part.

*Why not the V94 national median.* V94 gives every master one vote, so the oblasts where most of our
masters work set «the country»: with half of them in Kyiv and Lviv, every other oblast reads
«cheaper than the country» when it means «cheaper than Kyiv and Lviv». One vote per oblast makes the
baseline the price of a typical oblast. V94 stays as it is — it still drives the admin's «Від майстрів»
proposals for the default catalog, which is a different question (what a NEW master is given).

**3. Oblast factor (`regional_index`).** For each oblast: every number of its masters on a position
that HAS a baseline, as a percent of that baseline (4 decimals); each master's percents → their plain
median (one ratio per master, so a master with 50 positions counts once); the masters' ratios → the
IQR trim → if 3+ masters remain, their median ÷ 100, rounded HALF_UP to 3 decimals. `masters_count` =
masters left after the trim, `positions_count` = distinct positions compared. 1.10 = this oblast's
masters charge 10 % more than a typical oblast.

**Example** (the integration test): one work priced by Волинь 400 / 420 / 440, Тернопіль 300 / 320,
Рівне 360, and a master with no oblast at 1000. Oblast medians: only Волинь (420; Тернопіль has two
masters). Votes: Волинь 420, Тернопіль 310, Рівне 360 → baseline 360 (the 1000 is not there). Волинь's
ratios 111.11 / 116.67 / 122.22 % → factor 1.167.

**What reads it.** Only the admin page today. Step 2's «орієнтовно» price for a work an oblast has no
median for = `regional_baseline.median_price × regional_index.factor`, both from the same run; a work
with neither → the plain baseline, said as «по країні»; nothing at all → nothing shown.

**Thresholds in one place.** `PriceInsightMath.MIN_MASTERS` = 3 (oblast median, factor);
`RegionalPriceService.MIN_REGIONS` = 3 (baseline). Changing either changes what the admin sees on the
next run; nothing is stored that would need migrating.

## «Ціни по областях» as built (2026-11-25, step 2)

PRO (`Feature.REGIONAL_PRICES`, PRO + TEAM; FREE gets 403 and the PWA shows the pitch). Catalog → third
tab «По областях» (`/catalog/regions`): an oblast picker (his own by default, else Kyiv), a line with
that oblast's factor («Ціни тут +8 % до середніх по країні», or «замало майстрів» without one) and the
run's date, a chip «З цінами області», search, and his catalog's works grouped by trade → folder. Each
row: his price · the oblast's (median «майстрів: N», or «~N орієнтовно») · the country's (the baseline,
else the library price). `GET /api/catalog/regional-prices?region=` → `RegionalCatalogPriceService`;
rows carry the catalog item id only and the PWA joins them to the cached catalog. A work with no figure
at all is not listed; `%` lines and materials never. Read-only — taking a price into his catalog is a
later step if masters ask. Tests: `RegionalCatalogPriceIntegrationTest` (median, estimate 473,2 → 470,
library fallback, name key, empty oblast, FREE refused), `CatalogControllerTest`,
`RegionalPricesPage.test.tsx`.

## Decisions waiting for the owner

1. ~~**«Ціни по областях»: FREE or PRO?**~~ **Decided 2026-11-25 (owner):** the price hint is FREE; the viewer of other oblasts is PRO.
2. ~~**Threshold:** 3 masters (as today) or 5?~~ **Decided 2026-11-25 (owner): 3 for every oblast, his own and another** — no separate threshold of 5; the "Privacy" section above is superseded on this point.
3. **Start step 1 now** (data and admin only), look at the real per-oblast figures in a week, then
   decide about the viewer?

## Market prices from other sources — discussed 2026-11-24, nothing decided

**The need (owner).** Masters ask for prices «з таких от додатків, з rabotniki.ua» so they can
orient themselves — and want to see them IN Majstr, «щоб не ходити в 5 чи 6 інших застосунків для
порівняння, а щоб було все в одному місці». Our own medians (above) answer «what Majstr masters
charge»; this is the other half, «what the market says».

**The line we keep.** Looking at and comparing single prices from public sources is fine — a price
is a fact. Copying one source's database wholesale (by script or by hand, every oblast × position)
is not: Ukraine's 2023 copyright law protects databases, even non-creative ones, against extracting
a substantial part, and masters who use both apps would recognise the figures. Claude declined to
script an extraction of «Будівельні розцінки UA» (`bud.work.pricefreeua`); the owner may look at it
himself as one source among several.

**Options (from the discussion, cheapest first):**

1. **Links out.** Beside a position: «Порівняти ціни: rabotniki.ua · kabanchik · …», each opening
   that site's search for this work (in his oblast where the site supports it). One tap instead of
   searching; legal; quick to build. No figures inside Majstr.
2. **Our own market range «від–до», from many sources.** Quarterly, for the key works of the main
   trades × oblasts, we compile a range from several public sources (rabotniki.ua, kabanchik, firms'
   price lists, OLX, that app) and show OUR conclusion: «ринок: 280–350 ₴/м², Львівська, листопад
   2026», with the date and «за відкритими джерелами». Aggregating facts into our own range is market
   research (the default catalog was built this way); copying one source whole is what it must not be.
   The owner (or a helper) collects; Claude turns the notes into ranges and a migration. Kept apart
   from `regional_*` (masters' data) — never mixed into those medians.
3. **Partnerships.** Write to rabotniki.ua, kabanchik and the app's developer (bkgeorgiy@gmail.com):
   links and traffic from Majstr for their data, or a licence. Slow, the cleanest.
4. **Our oblast medians** (step 1, already weekly) grow on their own; one «Ціни на ринку» block could
   show both: «майстри Majstr у вашій області» and «ринок за відкритими джерелами».

Claude's suggestion: 1 first (fast, useful now), 2 in parallel for the most-used trades.

**Decided 2026-11-26 (owner): 1 now, 2 later.** «Порівняти:» under the price field offers rabotniki ·
kabanchik · budver · OLX (PWA 1.93.1, `COMPARE_SITES` in `PriceHint.tsx`), each a Google `site:` search
for the work — one URL shape that does not break when a site moves its own search page. Option 2 waits
for someone to collect the prices. The row stands under every work, our figure or not (same day).

**Renamed wordings (2026-11-26).** A master whose catalog kept an old library wording (V167 kept his copy
when he already owned the new one) got no hint: the library no longer has that name. Every rename is
recorded in `catalog_renamed_position`, so `CatalogRenames` answers the old wording with the new one's
figures (hint and «Ціни по областях»), and both weekly runs count an old wording as the new one — one
work, more masters. Only renames we recorded; never a «similar» name.

**Six months, one «по країні» (owner, 2026-11-26).** Estimate lines count only from estimates made in the
last six months (prices rise); a catalog price always counts — it is his price now. The hint's «По
країні» is the country baseline first, the library only without one — the same figure as the «По
областях» country column, so one work never shows two different «по країні».

**Markup copies do not count (owner, 2026-11-26).** «Наші ціни — середні по країні, ними майстри
користуються; націнка — заробіток бригадира: сьогодні +15 %, завтра 30, потім 5 — такі скачки ламають
ціни.» A copy made with a markup or a discount is left out; the original estimate and the catalog — the
base prices — count. So a brigadier's catalog counts as it is.

**Questions for the masters (owner's training, 2026-11-24 evening):**

- Де ви зараз дивитесь ціни, коли рахуєте кошторис? Які сайти / застосунки (назвіть усі)?
- Що саме шукаєте: ціну конкретної роботи, «від–до», ціну у своїй області чи в іншій?
- Чого достатньо: посилання «порівняти на rabotniki.ua» одним тапом — чи треба цифри прямо в Majstr?
- Довіряєте більше цінам інших майстрів Majstr (реальні кошториси) чи цінам із сайтів? Показувати обидва?
- Як часто ціни мають оновлюватись, щоб їм вірити (місяць, квартал)?
- Які 10 робіт для вас найважливіші, щоб почати з них?
- Чи готові вказати свою область і чи показувати ваші ціни (анонімно, медіаною) іншим?

## Not decided / later

- ~~Whether imported price lists or catalog edits count too.~~ **Decided 2026-11-26 (owner): yes**
  (V225). Most masters send a PDF and never have it signed, and many keep their rates in the catalog.
  Estimate lines of every status but REJECTED were already in; now a catalog row joins them as one more
  price of that master — only when `own_price` (typed, edited or imported). Every master is seeded with
  hundreds of library positions at OUR price; counting those would make every median our library
  price. Backfill: MANUAL/IMPORT rows; a LIBRARY row whose price differs from the same trade's library
  row and is not an old price from a PRICE_DRIFT notice. «Оновити мою ціну» on a notice clears the flag.
- A third-party price app (`bud.work.pricefreeua`, «Будівельні розцінки UA») was looked at
  2026-11-24: its data is not taken. At most its region model is studied from screenshots, or the
  developer is asked about a licence.
