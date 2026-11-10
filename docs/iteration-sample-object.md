# Iteration — the training sample «Приклад: …» (V197)

**Status:** built 2026-11-09, NOT committed (the owner checks locally first). Started by one session,
finished by the next. Built ON TOP of the uncommitted «Перший досвід майстра» (V196, PWA 1.74.0) in the
same checkouts, by the owner's choice. **Migrations:** V197. **PWA:** 1.75.0. Gate figures: «Gates».

## Why
A newcomer afraid of losing one of his two free objects (V107 counts every object ever made) does not
try the app; at a webinar he makes a test object and loses half of the free plan. The sample is an
object the server fills itself, that the cap does not count, that is deleted freely, and that no figure
reads.

## Owner's decisions (2026-11-09)
- Nine samples from the trades' default bundles, quantities filled (`SampleObjects`); GENERAL, OTHER and
  no trade get «Приклад: ванна 4 м²». Every line checked against the library: priced, 137 lines.
- One LIVE sample per account; deleting it lets the master make it again.
- The client sees «Навчальний приклад Majstr» on the portal, the preview and the PDF (anti-abuse: the
  sample works fully, so without the mark it would be a free third object).

## Backend
- V197 `projects.sample` (NOT NULL, default false, never updated) + the partial unique index
  `ux_projects_one_sample_per_owner`; `Project.sample`; `ProjectResponse.sample`.
- `SampleObjects` (content), `SampleProjectService.create` — the user-row lock, 409
  `SAMPLE_PROJECT_EXISTS`, `LimitService.reserveProjectSlot` never called; each line priced from the
  master's catalog by name in the sample's own unit, else the library's suggested price; a line the
  library no longer knows is left out rather than shown at 0 ₴. `POST /api/projects/sample`
  (`SampleProjectRequest { trade? }` — the trade just picked; absent = his first trade with a sample).
- `ProjectService.delete` skips the B-70 guard for the sample (a signature or money in it rewrites
  nothing the master ever read).
- **Read by no figure:** the dashboard, the admin funnel / by-source / markup counts, the admin user
  list and detail, broadcast segments (`findEstimateFillByOwnerIdIn`, `findLatestDraftId`), «Мої гроші»
  (owner-wide queries skip it unless that object is asked: `(:projectId IS NOT NULL OR p.sample = false)`),
  community prices and catalog usage, the PDF log (`PdfRenderLog` writes nothing for it — its row would
  outlive it with a NULL object). Kept on purpose: the unread-messages bell.
- Acts in the sample are numbered «П-N» (`WorkActRepository.maxSampleNumberSeqForUser`), outside the
  master's own sequence (`maxNumberSeqForUser` reads only numbers that START with a digit).
- **The mark «Навчальний приклад Majstr»** (`SampleMark`): a band at the top of the estimate PDF and the
  act PDF; `PublicEstimateView.ProjectSummary.sample` and `PublicActView.sample` drive a band on the
  portal (`?t=`, `?p=`, `?e=`, the act link and both previews). **The signed snapshot records it**:
  `SignedSnapshot.ProjectPart.sample` is a `Boolean` written ONLY when true (`@JsonInclude(NON_NULL)`),
  so every snapshot of a real object — and every one stored before V197 — keeps exactly its canonical
  text and hash; the accessor is `marksSample()`, not `isSample()`, which Jackson would have written as a
  second key (`SignedSnapshotTest` caught it). The detached object rebuilt from a snapshot carries it.

## PWA (1.75.0)
- `projectsApi.createSample(trade?)`; `useSampleProject` — online only (`useOnlineGuard`: offline the
  tap says a connection is needed and asks nothing); a 409 says «Приклад уже є — він у списку обʼєктів»
  and opens the list.
- «Подивитися приклад» on the empty home (with «Не рахується в ліміт обʼєктів…») and on `/welcome`
  once a trade is picked («Подивитися приклад кошторису»: the answer is saved first, then THAT trade's
  sample opens).
- «Приклад» badge on the object card; a banner on the object («Це навчальний приклад…», what to try,
  that the client sees the mark) with «Видалити приклад»; the list row offers «Видалити приклад» at
  ANY stage (a real object keeps «delete only once closed»). The delete is the ordinary object delete
  (it queues offline).
- The dashboard's metrics stay hidden while the only object is the sample (no figure reads it, so they
  would be three zeros).

## Not changed / confirmed
- The FREE cap, the lifetime count and every limit for real objects.
- The open question «FREE cap counts every object ever made» stays IN_PROGRESS until the owner decides
  the sample answers it.

## Tests
- Backend: `SampleProjectIntegrationTest` (every line priced in the library; a whole priced estimate,
  cap untouched, a second → 409; the picked trade chooses; signed + paid → deleted, made again; signed,
  sent and printed → funnel, handover sets, PDF log and the dashboard unchanged; «П-1» while his own
  act is «1»; the preview views carry the mark), `ProjectControllerTest` (201 with/without a body, 409
  code), `SignedSnapshotTest` (the mark written only for the sample; an old snapshot reads as real),
  the contract snapshot (`SampleProjectRequest`, copied to the PWA).
- PWA: `useSampleProject.test` (save → create → open; 409; offline), `ProjectRowMenu.test` (delete at
  any stage for the sample only; the badge), `ProjectDetailPage.test` (banner + delete; none on a real
  object), `TradesOnboardingPage.test` (button after a trade; save before create), `DashboardPage.empty
  .test` (the button; metrics hidden with only the sample).

## Gates (2026-11-09, together with the second pass of iteration-first-experience)
- Backend `./gradlew build`: green, 2452 tests, none failed or skipped.
- PWA: lint, `tsc -b`, `typecheck:tests`, vitest (1881 in 191 files), `vite build` — green;
  `test:e2e:offline:shell` 12/12.
- **Not run yet:** the e2e on a real backend and the 375 px walk (empty home → «Подивитися приклад» →
  preview → sign → delete; the object share after «Пропустити»). The local backend was stopped for low
  memory; both wait for the owner's go to start it again.

## «Новий обʼєкт» reads the lifetime count (PWA 1.75.3, 2026-11-10)
The FREE banner on `/projects/new` printed a literal `{{used}}`: `limits.objectsHint` names `used`, and
`NewObjectPage` passed only `max`. Fixed, and the page's cap check moved off the live list length onto
`limits.projectsUsed` (V107) like the home and the list — counting the list would block a master whose
only extra row is the sample. `NewObjectPage.test` pins both (the number shown; a sample in the list
does not block).
