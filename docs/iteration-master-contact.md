# Keeping masters in touch (V226, PWA 1.94.0)

**Status:** built 2026-11-26, not pushed yet (waiting for the owner's OK). **Prompt:** «зробити так, щоб майстри
були з нами на зв'язку» — four linked changes in both repos.

## 1. The bot tells the master (backend)

- **`MasterNotifier`** (`service/`) is the ONE door for telling a master something happened: the web push as
  before, plus the same news in his Telegram chat. `Notice(title, body, url, telegram)` — the push keeps its
  own words; the bot's line is shorter and `telegram = null` keeps a notice push-only.
- Both channels leave **after the commit** (`AfterCommit`), each registered separately so one failing (a full
  pool) never costs the other. Push stays `@Async("pushExecutor")`; the bot's send is
  `TelegramNotifier.send` on its own pool `telegramNotifyExecutor` (2 threads), fail-soft with
  `BackgroundErrors.capture("telegram-notify")`.
- **Who gets it:** `TelegramLinkService.notifyChat` — a linked chat AND `users.telegram_notify` (V226, default
  true: linking the bot is the opt-in). **403 from Telegram** (blocked bot, deleted account) →
  `TelegramClient.deliver` answers `BLOCKED`, the chat is unlinked and logged; any other failure changes
  nothing.
- **The «Відкрити в Majstr» button** needs an https URL — Telegram refuses a whole message over a localhost
  button, so off https the link rides in the text.
- **Events moved onto it:** estimate signed (`/estimates/{id}` — the push used to open the object), a
  client's question on an estimate or an act, a message by link, «Я оплатив», act signed, trial ending,
  PRO ending. **`MessageFileRetentionService` stays push-only** (`telegram = null`): housekeeping that the
  file also shows in the app; the bot is kept for what a client did and for the plan, so it is never muted
  as noise.
- **What the bot's line never carries:** the client's name, phone or words (a message says only that one
  came, with 📎N), any crew price or markup (estimate names go through `ClientSafeName`). Sums only where the
  client set them himself («Я оплатив»).
- **The switch:** `PUT /api/telegram/notifications {enabled}` (caller's own row), `notifications` in
  `GET /api/telegram`. PWA: «Сповіщення в Telegram» under the bot row — the whole line is the switch.

## 2. The master's number through the bot (backend)

- Right after a successful `/start <code>` the bot sends the new greeting (notifications first, then
  tasks and voice) and a reply keyboard «📱 Поділитися номером» (`request_contact`) + «Не зараз». It is
  **`is_persistent`** with an input placeholder «Поділіться номером кнопкою нижче 👇»: folded into an icon (the
  default, and Telegram Desktop's way) nobody found it (owner, 2026-11-26). The number or «Не зараз» takes it
  away (`remove_keyboard`); `/phone` asks again. «Не зараз» and `/phone` are commands — never an inbox note.
- `message.contact` is taken only when `contact.user_id == from.id` — his own number, never a forwarded
  card of a client or a supplier. `ContactPhone.normalize` → `+380XXXXXXXXX` (another country:
  `+<digits>`). Stored in **`users.contact_phone` + `contact_phone_at`** (V226); a second share overwrites.
- **Never printed, never shown to a client** — unlike `users.phone`. It reaches `phone` only by the master's
  tap: `POST /api/profile/phone-from-telegram`, and only while `phone` is empty.
- Admin: the call list shows it under the phone with «· Telegram»; the user card shows it beside the phone.

## 3. The bot offered at the right moment (PWA)

- `TelegramOfferHost` (mounted in `App`): a bottom sheet after an estimate really left the app — shared,
  copied or emailed from `SharePortalSheet` (not the economy portal). Only when this server has a bot and the
  chat is not linked; «Підключити» = the profile row's flow (`useConnectTelegram`: the tab opens in the tap,
  before the await) and «Не зараз».
- **Once per account, remembered in localStorage** (`majstr.telegramOfferSeen.<userId>`) — the trial offer's
  and the help card's rule: no column, no endpoint, no outbox entity; a second phone shows it once more, and a
  linked bot hides it everywhere because the host asks the server for the status. The trial offer goes first
  after the same send — two sheets never stack. The bot's status is fetched only after a send, not on every
  screen.
- The sheet's title drops the spec's «відкриє»: nothing tells the master when a client OPENS a page, so the
  sheet does not promise it.
- `TelegramRow`: notifications first in the subtitle, the switch when linked, and «Додати номер … із Telegram
  до документів?» while the profile has no phone.

## 4. A wider «Допомога з налаштуванням» (backend + PWA)

- The server decides (`GET /api/profile/help-offer` → `{reason, phone}` or 204, `HelpOfferService`):
  `NO_OBJECT` — a day in, no object of his own (the training sample does not count — it used to);
  `NOT_SENT` — a week in, an object, and nothing handed to a client: no estimate SENT/SIGNED and no PDF made
  in the app (the funnel's «Віддали клієнту», per master). Each has its own words; the field starts with
  `contact_phone`.
- When he asks, the server stamps **`users.help_reason`** (V226, CHECK `NO_OBJECT`/`NOT_SENT`) from the same
  rule — the client sends nothing new. Admin call list: column «Причина» («немає обʼєкта» / «не надсилав
  клієнту»).
- «Ні, дякую» on this device stays as it was (one key for both reasons).

## Not changed / confirmed

- Registration still does not ask for a phone (V190).
- Admin broadcasts stay email-only (open-questions: «Admin broadcasts through Telegram»).
- The open item «Майстер уже отримав сповіщення» is not closed: Telegram makes the promise truer for a
  master with the bot, not for one without.

## Gotchas

- `notify` cannot be a record component (it clashes with `Object.notify()`): the status field is
  `notifications`.
- Python heredocs in this environment turn `\\n` into a real newline inside a Java string — edit Java
  strings with escapes through the editor, not a script.
- Tests that build these services by hand get a REAL `MasterNotifier` over a mocked `PushService`, so their
  push assertions still read the push itself.

## Tests

Backend: `MasterNotifierTest`, `TelegramNotifierTest` (sent / off / no chat / blocked → unlink / failure
kept / localhost link), `ContactPhoneTest`, `TelegramControllerTest` (caller's own switch, missing value
400), `MasterContactIntegrationTest` (switch touches only the caller; own contact saved + overwritten,
someone else's refused; phone into the profile only by the tap and only while empty; both help reasons,
SENT and a PDF hide the card, reason stamped on request), `PublicEstimateServiceTest` (bot line without the
client's name or sum; a throwing Telegram never fails the signature), `PublicActPortalServiceTest`.
PWA: `TelegramRow.test.tsx`, `TelegramOfferHost.test.tsx`, `HelpOfferCard.test.tsx`; contract snapshot
gained `TelegramNotificationsRequest`.
