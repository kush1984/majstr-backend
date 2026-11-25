# Two new trades and the Lviv price sheet's gaps (V224, PWA 1.91.0)

**Status:** built 2026-11-25. **Source:** a Lviv-oblast «середня ціна» price sheet (76 screenshots,
29 sections, 560 lines, owner's folder `C:\Work\Прайси\Львів і область`).

## What changed

- **Two trades:** `DOORS_WINDOWS` («Двері та вікна», 🚪, teal) and `CLIMATE` («Клімат і вентиляція»,
  ❄️, indigo), after METAL in the enum and in V118's ranking. A door fitter or an AC installer is a
  speciality of its own; a floor layer who also fits doors ticks the trade rather than wading through
  BUILDER. Wherever a trade is constrained (six CHECKs) both are allowed; `template_trade_override`
  keeps its list (no default bundles for either, as for METAL). PWA: `Trade`, `TRADE_VALUES`, emoji,
  badge colour, `trades.*` in both locales; admin and portal lists.
- **161 new positions** in eleven trades (BUILDER 44, DOORS_WINDOWS 23, PLUMBING 22, CLIMATE 20,
  PAINTER 14, DEMOLITION 13, ELECTRICAL 9, METAL 7, TILING 4, DRYWALL 3, FLOORING 2) plus **10 shared**
  into the new trades at their current price (V220: a row per trade) — door / window removal, drip
  edges, the AC power line, ventilation duct and grille. V176 / V216's shape: one catalog version,
  nothing pushed, «Додати нові з довідника».

## How the prices were set

Every line of the sheet was compared with the library (a Python `re.I` probe — Git Bash `grep -i`
does not fold Cyrillic case). For each job the library lacked, 3–7 public prices were collected
(rabotniki.ua / budver.com city averages, firms' price lists, kabanchik) and the **median** shipped,
rounded to 10 ₴; the Lviv figure is ONE source. The migration's comment under each row names them.
44 positions only the Lviv sheet prices ship at the Lviv figure (owner ruling, `[one source]`);
35 rest on two (`[thin]`). Brick-wall demolition 1,5 / 2 bricks ships at the market median 270 / 300
although the library's «в цеглину» is 380 (owner ruling). Lviv and the national medians agree on the
whole (median ratio 1,0); AC installation is the outlier, ≈ 35–40 % cheaper in Lviv.

## Owner rulings (2026-11-25)

- Add everything, one-source rows at the Lviv price.
- **No renames of established names** — «короїд», «ондулін», «Армстронг», «Грильято» stay: masters
  know them by these words.
- A full price re-check of the library every 2–3 months (V176 was 2026-10-25 → next ≈ late January
  2027).

## Not done

- No material norms for the new positions (open question «V176's new positions have no material
  norms»).
- No default bundles for the two new trades.
