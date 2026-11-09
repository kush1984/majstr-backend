# Iteration — share without verifying the email (2026-11-09, before the 11.11 webinar)

**Status:** built 2026-11-09, NOT committed (the owner checks locally first). After the second review
pass: backend `./gradlew build` green (2422 tests, none failed or skipped); PWA lint, `tsc -b`,
`typecheck:tests`, vitest (1842 in 185 files) and `vite build` green, `test:e2e:offline:shell` 12/12,
e2e on a real backend 7/7. **Migrations:** none. **PWA:** 1.73.0.

Why a file of its own rather than a seventh part of [iteration-registration-friction.md](iteration-registration-friction.md):
this changes the SEND rule and the definition of a funnel step, not the registration door.

## Why
The admin funnel: 95 masters made an estimate and 14 shared it (15 %). Once an estimate reaches the
client the product works (5 signed, 4 masters paid). 45 % never verified their email, and without it
they could neither share nor download the estimate PDF — we blocked the most important step ourselves.

## The rule (owner's ruling)
- **What the MASTER sends himself**: a copied link, the phone's share sheet, the object portal, the act
  link, the estimate PDF. Only a NAME (`SendReadiness.masterSendsItself`, 403 `SENDER_NAME_REQUIRED`).
- **What WE send from our domain** (every `send-email`): a verified email AND a name
  (`SendReadiness.weSendTheLetter`) — the domain's reputation at Resend.
- **Unchanged:** the PRO trial, checkout, AI on FREE, changing the email — they keep their own checks.

## Call sites

| Where | What | Rule |
|---|---|---|
| `ShareLinkService.create`, `markSent` | mint `?t=` / the link left | name |
| `ShareLinkService.sendByEmail` | our letter | email + name |
| `ProjectPortalService.update`, `markSent`, `updateEconomy`, `updateAct` | publish | name |
| `ProjectPortalService.sendEmail`, `sendEconomyEmail`, `sendActEmail` | our letter | email + name |
| `EstimateService.renderPdf` | estimate PDF | name (was email + name) |
| `WorkActService` live PDF, `signOffline` | already name-only | renamed call only |

`featureGuard.requireFeature(CLIENT_PORTAL)` stays as it was, before the readiness check.

## What shipped — backend
- `SendReadiness`: `require`/`requireName` → `weSendTheLetter`/`masterSendsItself`; the javadoc states the rule.
- **The verification letter** (`ResendEmailService.verificationHtml`) no longer says «щоб надсилати
  кошториси клієнтам»: verify to keep access to the account, take the PRO trial and send the estimate
  to the client by letter; sharing in Viber/Telegram and the PDF work without it. No number of days in
  it. FAQ, JSON-LD and the privacy policy promised nothing of the kind — left alone.
- **`trialDays` on `GET /api/plan/limits`** (`PlanLimitsResponse`, from `app.billing.trial-days`) —
  a tariff setting, the same for everyone, so it lives beside the limits and not on `/me`.
- **The funnel's `shared` step is REDEFINED** — a deliberate change of meaning. It was «owns a link
  row» over both link tables; the PWA mints a link when the share sheet OPENS, so a master who only
  looked counted, and with the gate gone that inflation would land on the very step this iteration is
  measured on. Now: **owners with an estimate that reached SENT or SIGNED**
  (`EstimateRepository.findOwnersWithEstimateIn`). SENT is set only by `ShareLinkService.markSent` /
  `sendByEmail` and `ProjectPortalService.markSent`; the one way back to DRAFT is reopen
  (`REOPEN_ENABLED = false`); acts exist only on a signed estimate, so they add nobody. `MetricsService.
  sharedOwnerSources()` is the ONE computation behind the aggregate, the by-source row and the new
  `sharedUnverified` (Σ over sources == aggregate still holds). The link-row queries
  (`findSharedOwners` ×2, `countByOwner`, `existsByProjectOwnerIdAndKindIn`) and
  `ShareLinkKind.SHARED_WITH_CLIENT` had no other reader and are gone. The admin user card's «поділився»
  (`AdminUserService`) follows the same rule (`sent + signed > 0`) — its own javadoc demanded that.
  Caveats, printed under the funnel: before 2026-10-18 SENT was set as the sheet opened, so old figures
  are slightly high; a sent estimate later deleted drops out.
- **`ActivationFunnelResponse`**: `verifiedEmail` is no longer a step of the chain; new
  `sharedUnverified` = sharers whose email is still unverified today (a master who verified later
  leaves it). The admin page reads registered → object → estimate → shared → signed and shows
  «Підтвердили email» and «Поділились без підтвердженої пошти» beside it.
- **The ⚠️ on the admin user list** stays, re-worded: active but unverified — a candidate for a call.
  It no longer means «PDF bypass».
- **`AudienceRules`**: UNVERIFIED stays FIRST, and the javadoc says why — an unverified address may be
  someone else's or mistyped; product letters there risk spam complaints against our domain, so the one
  letter that belongs there is «підтвердіть пошту».

## What shipped — PWA
- `useSenderGate`: **`emailFirst` is gone.** The name is asked always, before anything leaves — an
  unverified master without a name used to be let through and got the 403 afterwards.
- `EstimateEditorPage`: no `ensureEmailVerified()` before PDF and «Поділитися».
- The email is checked only on «надіслати листом» (`SharePortalSheet.onEmail`, `ActShareSheet.onEmail`,
  via `useEmailGate`), and `EMAIL_NOT_VERIFIED` from the server still opens `EmailVerifyModal`.
- **The trial card** (`features/email/trialOffer.ts` + `TrialOfferHost`, mounted in `App`): after a
  link really left (shared or copied, estimate / portal / act), an unverified FREE master who never took
  the trial gets a bottom sheet «Підтвердіть пошту — і зможете ввімкнути N днів PRO», N from `trialDays`;
  «Надіслати лист ще раз» / «Пізніше». **Remembered on the device**, per account, like `HelpOfferCard`:
  who qualifies is the server's `me`, the device holds only «seen». A second phone shows it once more —
  harmless, and it spares a column, an endpoint and an outbox entity before the webinar.
- `billing.tryTrial` takes the number from `trialDays` (plural forms); `email.bannerText`/`modalText`
  rewritten (uk + en); the «5-day» comment in `api/billing.ts` fixed.

## Not changed / confirmed
- The act's live PDF and `signOffline` already needed the name only.
- The PRO trial, checkout and AI on FREE still answer 403 `EMAIL_NOT_VERIFIED` without verification
  (`RegistrationOnboardingIntegrationTest.anUnverifiedMasterSendsHimself_butNotThroughOurLetters`).

## Review follow-ups (2026-11-09, same round)
- **`PUT /api/estimates/{id}` never writes SENT, and never takes SENT back to DRAFT**
  (`EstimateService.update`). SENT is a fact the server records — `mark-sent` or our letter — so an
  editor opened before the share (its cache still says DRAFT, or a rename queued offline and replayed
  after `mark-sent`) no longer unmarks «Надіслано», «Очікує» on the dashboard or the funnel's `shared`
  step. The stale status is ignored, the rest of the request (name, notes, validity) still lands; the
  request contract is unchanged. REJECTED is still accepted, and still unreachable from any UI — see
  the open question «`EstimateStatus.REJECTED` is unreachable».
- **The offline signature's client copy is OUR letter** (`WorkActService.signOffline` →
  `ActSignedCopyService.freezeAndSend(…, mayMail)`): from an unverified account the act is still signed
  and frozen — the paper already happened — but the copy is not mailed, and the response carries
  `clientCopyWithheld: true` (absent otherwise). The PWA then says «Копію клієнту не надіслано —
  підтвердіть пошту». The client's portal signature is unchanged: its copy still goes out.
- **The trial card waits for the screen, not a timer** (`TrialOfferHost`): armed by the send, it opens
  when the document is visible AND no toast is on screen — after «Поділитися» to Viber on the master's
  return, after «Копіювати» once «Скопійовано» fades. Toasts themselves are untouched.
- **The card's promise is now honest.** Verifying does not turn PRO on: the title reads «Підтвердіть
  пошту — і зможете ввімкнути N днів PRO», and `VerifiedTrialCard` on the dashboard asks once
  «Пошту підтверджено — увімкнути N днів PRO?» (`useStartTrial`, the same call the Profile button
  makes; «Пізніше» remembered on the device). It appears only for an account this device once saw
  UNverified (`noteVerificationState`, from the always-mounted host) and that is now verified, FREE and
  never took the trial — so a master who verified long ago is not suddenly offered it, and one who
  verified in the mail app's browser sees it on his next open.

## Review follow-ups, second pass (2026-11-09)
- **Starting the trial re-asks the plan limits** (`useStartTrial` invalidates `PLAN_LIMITS_KEY`): the
  limits follow the plan, and without it the dashboard kept «2 обʼєкти за весь час» and a disabled
  «Новий обʼєкт» for up to the 5-minute `staleTime` after «Увімкнути PRO».
- **The one exception to «our letter needs a verified email»: the client's PORTAL signature.**
  `PublicActPortalService.sign` mails the act's signed copy without asking about the master's email —
  deliberately: the client started that letter by signing, and withholding their own copy of what they
  just signed would punish them for the master's account. Written into `ActSignedCopyService`'s javadoc.
- `EstimateService.update`'s SENT guard is bracketed (`SENT || (SENT && DRAFT)`) — same logic, readable.

## Open
- A direct PDF counter («віддали клієнту = поділились ∪ PDF») — next iteration.

## Tests
- Backend: `ContractorNameTest` (both doors), `ShareLinkServiceTest` (mint + mark-sent unverified;
  `sendByEmail` unverified → `EMAIL_NOT_VERIFIED`, nothing sent), `ProjectPortalServiceTest` (publish /
  mark-sent / economy / act unverified pass; all three `send-email` refuse), `EstimateServiceTest` (PDF
  unverified renders, nameless refused), `ResendEmailHtmlTest` (the letter's promise),
  `LimitServiceTest` (`trialDays`), `MetricsServiceTest`, `AdminUserServiceTest`,
  `ActivationFunnelSharedStepIntegrationTest` (rewritten: SENT/SIGNED, a minted DRAFT link is not
  sharing, `sharedUnverified`, admins excluded), `SourceBreakdownIntegrationTest` (fixture on statuses),
  `RegistrationOnboardingIntegrationTest` (the whole rule through the real server).
- PWA: `SenderSheetHost.test` (unverified without a name → the name sheet BEFORE sharing),
  `SharePortalSheet.test` (copy unverified: no modal, the card is queued; the letter button bounces;
  a server 403 still bounces), `ActShareSheet.test` (publish unverified; the letter needs verification),
  `TrialOfferHost.test` (N from config, once, never for a verified master or one who took the trial;
  waits for a hidden page to come back and for a toast to fade), `VerifiedTrialCard.test` (offered
  after the change, starts the trial, «Пізніше» once, never without the change), `ActEditorPage.test`
  (a withheld copy is said after the signature).
- Backend, review follow-ups: `EstimateServiceTest` (SENT + PUT DRAFT keeps SENT and renames; PUT
  SENT on a DRAFT keeps DRAFT), `ActSignedCopyServiceTest` (withheld / mailed / no client email),
  `WorkActIntegrationTest` (offline signature from an unverified account lands with the flag; a
  verified one carries none).
- PWA, second pass: `useStartTrial.test` (the limits are fetched again after the trial starts, and not
  when it is refused) — red with the invalidation removed.
