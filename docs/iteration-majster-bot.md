# Iteration — majster-bot (Telegram bot + inbox + object tasks)

**Status:** built on branch `majster-bot` (2026-10-31), merged onto master after the Google sign-in shipped
(2026-11-16) with the review fixes below. **Migrations:** V211 `majster_bot` (written as V181, renumbered at
each merge — master had taken up to V210), V212 `inbox_entry.forwarded`.

## Goal

A master leaving a site, standing on one or sitting in the van pulls out the phone and types — or
dictates with the keyboard's own microphone — what has to be done or added for an object: «на Бандери
докупити 3 мішки клею і грунт», «Сихів — додати демонтаж плитки 12 квадратів», «подзвонити електрику»,
«дав Василю 2000 на плитку». The bot files each thing where it belongs; whatever it cannot place waits in
an inbox the master clears at home.

Owner decisions (2026-10-31):
- Bot first (Telegram), the inbox comes with it; money included; voice = the keyboard microphone for now
  (server speech-to-text analysed, not built — see «Voice later»).
- FREE gets the bot (bounded by a daily parse budget, not a feature gate).
- «Що треба зробити» is its OWN entity — an object TASK with a done tick; notes stay reference material.
- What the bot is SURE about is written at once and answered with «Скасувати»; **money is never written
  without a confirmation** (a button in the bot or «Вхідні» in the PWA).
- The owner creates the bot in @BotFather; the token lives only in env (`TELEGRAM_BOT_TOKEN`).

## Rules (the invariants)

1. **Every message lands in `inbox_entry` first**, verbatim, before anything is parsed. The parse writes
   `inbox_item` rows; applying an item goes through the EXISTING service door for that record — never a
   new write path:
   | kind | door |
   |---|---|
   | `SHOPPING` | `ShoppingListService.addManual` |
   | `TASK` | `ProjectTaskService.add` (new) |
   | `NOTE` | `ProjectNoteService.add` |
   | `ESTIMATE_LINE` | `EstimateService.appendItems` (as dictation commit) |
   | `EXPENSE` | object → `ObjectExpenseService.add`; none → `CashFlowService.create` (EXPENSE) |
   | `INCOME` | object → `PaymentService.addReceipt` (unplanned); none → `CashFlowService.create` (INCOME) |
2. **The bot never creates an object or an estimate.** It matches only the master's existing ACTIVE objects
   (not COMPLETED/CANCELLED). An unknown object waits in the inbox; creating one stays the normal PWA flow
   with its FREE cap — the bot cannot hit `MAX_PROJECTS` / `MAX_ESTIMATES_PER_PROJECT`.
3. **An object the model GUESSED is never final.** Auto-apply needs the object to be NAMED in the text
   (the model returns `objectMentioned`) and resolved to one of the listed objects. Otherwise the bot asks
   with buttons (objects + «Без обʼєкта» + «Потім»); an unanswered question leaves the items in the inbox.
4. **An estimate line lands BY ITSELF only in the object's single DRAFT estimate** (not SENT — the client
   already has it —, not SIGNED, not ADDENDUM), priced by `CatalogMatcher` exactly as dictation is. Two
   drafts, or only a SENT one → `ESTIMATE` (the master picks in «Вхідні», a SENT one included); nothing open at
   all → `NO_DRAFT` («Додаткові роботи» on his tap); no price → `PRICE`. Never 0 ₴ behind his back.
5. **Money is PENDING until confirmed** — always, whatever the confidence.
6. **Undo** deletes the records an item created through the same services and flips it back to
   `DISCARDED`.
7. **Idempotency:** `inbox_entry.telegram_update_id` is UNIQUE — Telegram redelivers on any non-2xx.
8. **No model call inside a transaction**; the webhook persists and answers 200 at once, the parse runs
   `@Async`.
9. **Budget:** `InboxParseRateLimiter`, per account per day (`app.bot.parses-per-day`, env
   `INBOX_PARSES_PER_DAY`, 60). A FREE account with an unconfirmed email gets no parse at all (`AiAccess`, the
   dictation rule): the entry is FAILED with `Notice.VERIFY_EMAIL`.
10. **A forwarded message is someone else's words** (V212 `inbox_entry.forwarded`, Telegram's
   `forward_origin`/`forward_*`): nothing in it is filed by itself — every ready item waits on `CONFIRM`.
11. **The training sample is never a choice** — money under it would be read by no figure.

## Schema (V211, V212)

- `project_task` — id, project_id (FK cascade), body ≤ 500, done_at, sort_order, created_at, updated_at.
- `telegram_link` — user_id PK (FK cascade), chat_id UNIQUE, username, linked_at.
- `telegram_link_code` — code PK, user_id (FK cascade), expires_at (one-time deep-link code, 15 min).
- `inbox_entry` — id, owner_id, source (`TELEGRAM`/`PWA`), raw_text, status (`PARSING`/`NEW`/`DONE`/
  `FAILED`), telegram_update_id UNIQUE, telegram_chat_id, telegram_message_id (the bot's reply, edited in
  place), forwarded (V212), created_at, updated_at.
- `inbox_item` — id, entry_id (FK cascade), kind, status (`PENDING`/`APPLIED`/`DISCARDED`), project_id
  (FK set null), estimate_id (FK set null), text, quantity, unit, unit_price, item_type, amount,
  category, record_id (what applying created), issue, sort_order.

## Telegram

- Linking: PWA Profile → «Підключити Telegram» → `POST /api/telegram/link` → `t.me/<bot>?start=<code>`.
  `/start <code>` binds the chat; one chat ↔ one master; «Відключити» deletes the link.
- `POST /api/public/telegram/webhook` checks `X-Telegram-Bot-Api-Secret-Token`; local dev uses long
  polling (`app.telegram.mode=polling`), prod `webhook`, tests `off`.
- Callback buttons: `o:<entry>:<object>` (object choice), `c:<entry>` (confirm), `e:<entry>` («Додаткові
  роботи»), `u:<entry>` (undo; on money it first asks, `U:<entry>` deletes), `m:` / `r:<entry>:<object>`
  («Інший обʼєкт»), `v:<entry>` (redraw). Undo and move are refused 48 h after the message («Вже неактуально»).
  callback_data stays under Telegram's 64 bytes. Taps run on `botTapExecutor`, parses on `botExecutor`.
- A reply is cut to 3 900 characters and each line to 200 (Telegram refuses over 4 096). A refused
  `setWebhook` is a WARN at startup, not «registered».

## Voice notes (built 2026-11-16, V213)

Owner's call: voice is **PRO**; a FREE account with a verified email gets `app.voice.free-trial` (10) notes to
try, then «Голосові — у PRO» with a link (https only). Every plan: a note ≤ `max-seconds` (60), and a daily
budget of `seconds-per-day` (900) per account. `VoiceAccess.admit` decides on the webhook thread (DB only).
- Stored first, like text: `InboxService.receiveVoice` writes the entry with `voice_seconds` and Telegram's
  `voice_file_id`, empty `raw_text`. The parse fetches the audio (`VoiceAudio`, implemented by
  `TelegramClient.fetch`: `getFile` + the file host, ≤ 5 MB), transcribes it (`SpeechToText`, OpenAI
  `/v1/audio/transcriptions`, model `app.voice.model` = `gpt-4o-mini-transcribe`, language uk, the object names
  as the prompt, its own 200/h ceiling), stores the TEXT and clears the file id — the audio is kept nowhere.
  A failed transcription is a FAILED entry; «Спробувати ще раз» transcribes again while Telegram keeps the file.
- The reply starts with «🎙 «what was heard»», then the usual answer, then «Безкоштовних голосових
  залишилось: N» on the trial. Refusals: too long, verify email, trial over, daily budget, voice off (no
  `OPENAI_API_KEY`). The PWA marks a voice entry 🎙; the privacy policy names Anthropic and OpenAI.
- Not built: audio files other than Telegram voice notes (`audio`, video notes) — they get «text and voice only».

### The analysis it came from

Telegram voice = OGG/Opus via `getFile` (≤ 20 MB). Anthropic has no speech-to-text, so a second provider
(OpenAI `gpt-4o-transcribe`/`-mini` is the shortest path — `OPENAI_API_KEY` and an OpenAI client exist,
it takes ogg as is and a `prompt` with object names). New flow `app.ai.flows.transcribe`, max length 60 s,
a per-day MINUTES budget, audio discarded after transcription (only the text is kept), a privacy-policy
line, and a 20–30 real voice-note quality check before launch. After transcription the pipeline is the
same text parse.

## What shipped

**Backend** — `entity/{ProjectTask,TelegramLink,TelegramLinkCode,InboxEntry,InboxItem}`;
`service/ProjectTaskService` + `controller/ProjectTaskController` (`/api/projects/{id}/tasks`, the notes shape,
X-Entity-Uuid idempotent); `service/inbox/{InboxExtractor,InboxService,InboxParseRateLimiter}`;
`controller/InboxController` (`/api/inbox`: list, count, quick add, retry, undo, discard; items: PATCH, apply,
discard); `telegram/{TelegramClient,TelegramLinkService,TelegramBot,TelegramReplies,TelegramWebhookController,
TelegramStartup,TelegramController}`; `config/BotProperties` (`app.bot.*`); `AiFlow.INBOX`
(`AI_FLOW_INBOX`, pinned to sonnet). `PublicPortalRateLimitFilter` skips `/api/public/telegram/`.

**PWA (1.69.0)** — `api/{tasks,inbox}.ts` + types; `features/tasks/{useTasks,TasksSheet}` («☑️ Що треба
зробити» in the object's ⋮, beside notes; tick / retype in the row; offline outbox entity `task`);
`features/inbox/{InboxPage,InboxHomeStrip,TelegramRow,useInbox}` — `/inbox` (quick add with the Web Speech mic,
offline entity `inboxEntry`; waiting items corrected in the row: kind, object, quantity/unit, price, amount,
category, then «Записати»), a home strip, and Profile → «Telegram-бот». The quick add and retry ride the
LLM timeout (`api/client.ts`). `/inbox` added to `public/_redirects`.

## Running it

Env (Railway): `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` (without @), `TELEGRAM_WEBHOOK_SECRET` (a long random
string, `[A-Za-z0-9_-]`), `TELEGRAM_MODE=webhook` (the default); `PORTAL_BASE_URL` must be the backend's public URL
(the webhook is registered as `<it>/api/public/telegram/webhook` at startup); `APP_URL` = the PWA (default
`https://majstr.pro`). Optional: `INBOX_PARSES_PER_DAY` (60), `AI_FLOW_INBOX`.
Local: `TELEGRAM_MODE=polling` + a token — no public URL needed; note that polling deletes the webhook, so a
local run with the PROD bot's token steals its traffic. Use a second bot from @BotFather for local.

## Tests

`InboxIntegrationTest` (real Postgres, the model scripted, `InboxService` wired by hand so no second context):
named object → shopping/task/note filed and undone; guessed object → question → `chooseObject` files it; money
waits, then lands in `object_expenses` / `cash_entry` / `payment_receipt` and undo removes all three; estimate
line priced from the catalog into the one draft, an unknown one waits on PRICE and is filed after a PATCH;
Telegram redelivery = one entry; over budget = FAILED with the text kept; task order; link code once-only and
expiry. `TelegramRepliesTest`, `InboxExtractorTest`, `TelegramWebhookControllerTest`. `IdorMatrixIntegrationTest`
covers every new id-bearing route (tasks: classes 1+2; inbox: foreign ids + a body case re-filing B's item under
A's object). PWA: `useTasks.test.ts` (order), `client.test.ts` (LLM timeout for the parse and retry only).
Verified in a 375 px frame against a local backend: no horizontal overflow, filing a task and an expense from
«Вхідні» writes the rows, the task ticks in the sheet. Screenshots could not be taken (the browser tool timed
out), so the look itself was not seen.

## Not changed / confirmed

- The bot creates no object and no estimate; FREE caps are untouched.
- No new money path: expense/payment/cash go through `ObjectExpenseService`, `PaymentService.addReceipt`,
  `CashFlowService.create`.
- Voice notes, photos of receipts in the bot, and the evening reminder are not built (open-questions).

## Follow-ups from the first live test (2026-11-01)

- «отримано 3000 завдатку» on an object with a «Завдаток» plan stage was refused. The payment plan has since
  been removed (2026-11-05), so an INCOME is a receipt under its own text.
- Every estimate signed → a line used to wait with nowhere to go. Now `Issue.NO_DRAFT` (none open) is told apart
  from `Issue.ESTIMATE` (several open). NO_DRAFT: the bot offers «📋 Новий кошторис «Додаткові роботи»», the
  PWA a button (`POST /api/inbox/{entryId}/estimate` → `InboxService.createDraft`) — one DRAFT per object through
  `EstimateService.createForProject`, so the FREE cap of 3 holds (`Notice.ESTIMATE_LIMIT`, nothing created). The
  bot still creates nothing by itself: the estimate is the master's tap. ESTIMATE: the PWA lists the object's open
  estimates and `PATCH …/items/{id}` takes `estimateId` (only one of THAT object's open ones, else 404).

- «Інший обʼєкт» (owner's ask): under a reply that filed something the bot offers «🔄 Інший обʼєкт» (`m:`), which
  redraws the message as an object picker (`r:<entry>:<object>` / `-`, «← Назад» = `v:`).
  `InboxService.moveToObject` takes every filed record back through its own service and files it again under the
  chosen object; money the master already confirmed is re-filed without asking again; a record its service
  refuses to release (an estimate line whose estimate got signed since) stays and is named.

## Gotchas

- The owner's local DB holds the bot's history row moved to V211 — do not touch even V211's comments (checksum).
- `telegram_link` is one chat per master: binding a second chat MOVES the link.
- Bot replies are plain text on purpose (no Markdown parse mode).

## Review fixes (branch `majster-bot-fixes`, 2026-11-16)

From the review in `C:\Work\prompts\REVIEW-majster-bot.md` (outside the public repo), the five must-fix items:

1. **Money is written once.** `InboxService.apply` files an item in ONE transaction under the item's row lock
   (`InboxItemRepository.lockById`, PESSIMISTIC_WRITE) and re-checks PENDING: two taps on «Записати» (the bot
   and the app, or both bot threads) write one expense/receipt; a refused write rolls the APPLIED back with it.
2. **An entry is parsed once.** A replayed `POST /api/inbox` answers what is stored and never parses again;
   «Спробувати ще раз» goes through `retry`, which wins only a FAILED entry (`claimRetry`, an atomic
   `UPDATE … WHERE status = FAILED`); the items are written under the entry's row lock and only while it is
   still PARSING, so a second run that got through anyway files nothing.
3. **Nothing stays PARSING.** `process` catches every failure (not only the model's) and leaves the entry
   FAILED; `failStaleParses` (every 5 min) turns a parse older than 10 min — a deploy cut it — FAILED; a model
   number above 999 999 999 is a misreading (`InboxExtractor.MAX_NUMBER`), not a figure for NUMERIC(15,…).
4. **Stored before Telegram hears 200.** The webhook calls `TelegramBot.store` on the request thread (an
   ordinary message from a linked private chat → `receive`); only `parseAsync` (the model call) goes to
   `botExecutor`. A failure to store is a 5xx, so Telegram delivers again; a rejected parse stays PARSING and
   the sweep makes it FAILED, visible in «Вхідні».
5. **DRAFT only, by itself.** A line lands by itself only in the object's single DRAFT (`draftEstimates`); a
   SENT estimate is on the client's screen, so a line goes there only when the master names it in «Вхідні»
   (`openEstimates` still offers DRAFT + SENT for that explicit choice). No draft → `NO_DRAFT`.

The «Варто виправити» half, done in the same branch: `AiAccess` on every parse (rule 9); the sample left out
(rule 11); amounts and prices capped at 100 000 000 like every money form (`InboxExtractor.MAX_MONEY`);
forwarded messages wait (rule 10); undo/move expire after 48 h and money asks before it is deleted; taps on
their own pool; replies under Telegram's limit; undo and «Інший обʼєкт» take a record back under the item's
row lock (`takeBack`), so two taps delete it once; two simultaneous quick adds with one `X-Entity-Uuid` are one
entry (the race catch looks the id up, not the Telegram update); a foreign task id is a 404, not a 403; an
object deleted mid-parse keeps its number. Also: with only a SENT estimate open the line is `ESTIMATE`, never
`NO_DRAFT` — the latter offered a «Додаткові роботи» the tap would refuse to create.
Left for later: retention for `inbox_entry.raw_text`, the privacy-policy line about client names sent to the
model, the unused columns (`telegram_message_id`, `telegram_chat_id` reads, `project_task.sort_order`), and
`Outcome`/`Status` moving to `dto/`.
