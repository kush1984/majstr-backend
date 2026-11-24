# Masters rate Majstr — V218

**Status:** shipped 2026-11-23 (backend `1df6807`, PWA `07a398c`). **Migration:** `V218__app_reviews.sql`. **PWA:** 1.87.0.

## Why

The owner (2026-11-21): reviews of Majstr itself, not of a master — where a master writes one and how
other masters see it. Two jobs: what to fix (every rating reaches us), and proof for the next master
(a good one he agrees to show goes on majstr.pro). There was no way to leave feedback in the app at all
— only support@majstr.pro.

## What shipped

- **Where he writes.** Профіль → «⭐ Оцінити Majstr», always. And a quiet card «Як вам Majstr?» on the
  home screen, only when `GET /api/app-reviews/prompt` says so: an estimate of a real object sent or
  signed, account older than 7 days, not asked (answered or «Не зараз») and not reviewed in 90 days;
  admins never. A star opens the sheet with it picked.
- **The sheet.** Stars; 4–5 → «Що вам подобається найбільше?» + consent «Можна показати мій відгук
  іншим майстрам на majstr.pro (ім'я, напрям)», ticked by default; 1–3 → «Що нам поправити?», no
  consent at all. `POST /api/app-reviews`.
- **Admin «⭐ Відгуки».** Unread first, average rating, the master's email and phone to answer him.
  «Опублікувати» only on a consented one, «Прочитано» / «Зняти з сайту», «Нове». Unread count on the menu.
- **majstr.pro «Що кажуть майстри».** Between «Для кого» and «Контроль»: the 6 newest published —
  stars, the words if any, «Олена К.» and months in Majstr (read live). No reviews or no backend → no
  section (the landing still paints instantly).
- **Follow-up 2026-11-24 (owner).** The first published review was stars-only and never appeared: the
  landing dropped reviews without text while the admin let them be published. Now a stars-only review
  shows; trades and the estimate / signed counts are no longer rendered (too much room — the public API
  still returns them); the admin list puts who wrote it (name · email · phone) under the text, since
  the «Майстер» column was hidden on a phone.

## Rules (why they hold)

- Consent below 4 stars is impossible: forced false on submit, refused on publish (409), and a CHECK
  refuses `PUBLISHED` without consent for any writer.
- Only the first name and the surname's initial ever leave; never email, phone or full name.
- The landing's figures are read at request time — a review cannot claim activity the account lacks.

## Not done / decided

- No in-app reply to a review: the admin answers by phone or email from the list.
- Google: if we ever ask for Google reviews, ask EVERYONE — selective asking breaks Google's policy.
- No notification to the owner on a new review; the unread badge in the admin is the signal.

## Tests

- `AppReviewIntegrationTest` — when the home screen asks (success, first week, «Не зараз», the pause),
  a low rating never carries consent, only a consented review is published, what the landing shows
  («Олена К.», trades, months, counts), the CHECKs.
- PWA `AppReview.test.tsx` — card hidden unless asked, star → sheet → submit, «Не зараз», low rating has
  no consent, a high one can be kept private.
- 375 px screenshots: home card, sheet at 5 and at 2 stars, landing on phone and desktop.
