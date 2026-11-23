# Where a master works — V219

**Status:** built, not pushed. **Migration:** `V219__user_region.sql`. **PWA:** 1.87.0 (with V218).

## Why

The owner (2026-11-21) wants market prices by region — a master from one oblast looking at what the
same work costs in another, and in time a public base of prices. A regional median needs at least three
masters per position per oblast, so the answers have to start piling up long before anything can be
shown. This step only collects them («якнайшвидше»).

## What shipped

- `users.region`: one of the 24 oblasts or Kyiv city (`Region`), CHECK-listed; NULL = not answered.
- `PUT /api/profile/region` — its own write, so the profile form or the requisites cannot blank it;
  `UserResponse.region`.
- PWA: «У якій області працюєте?» on the home screen once he has a real object (not on the very first
  screen), a picker and «Зберегти»; «Пізніше» snoozes it 14 days on that device; gone once answered.
  Also «Область, де працюєте» in «Редагувати профіль». The «Оцінити Majstr» card waits until the region
  is answered — one question on the home screen at a time.

## Not done / next

- Nothing reads the region yet. Next: count answers per oblast in the admin; then `PriceInsightService`
  medians per region with a fallback to the whole country, shown to masters beside a catalog price.
- The object's own region (a Lviv master working in Kyiv) — not asked; the master's region stands in.
- Registration / the trades screen does not ask it yet; the home card covers new masters after their
  first object.

## Tests

- `UserRegionIntegrationTest` — saved and returned, a profile save leaves it, an unknown value refused by
  the API (400) and by the CHECK.
- PWA `RegionAskCard.test.tsx` — asked until answered, saves the pick, «Пізніше», 25 options in order.
- 375 px screenshots: home card with a pick, the profile field.
