# Iteration — where the client's page shows «Зведення» (V202)

**Status:** DONE 2026-11-12. **Migration:** V202. **PWA:** 1.77.2.

A master asked for the summary (Роботи / Матеріали / Разом per estimate, and the grand total) under the
positions, as it used to be. The owner kept the top as the default and made the place a choice.

- `projects.portal_summary_at_bottom` (default FALSE). One choice per OBJECT: the signature portal (`?p=`),
  the economy page (`?e=`) and every estimate's own `?t=` link read it — links already sent included.
  A layout choice, not a printed value: a signed `?t=` page reads the LIVE object's flag.
- `PUT /api/projects/{id}/portal/summary-placement` `{ atBottom }` → the `ProjectResponse` (new
  `portalSummaryAtBottom`). No feature gate. In the IDOR matrix.
- `PublicEstimateView.ProjectSummary.summaryAtBottom`; `static/portal/index.html` renders `renderSummary` in
  exactly one place: at the top, or after the estimate sections and before the photos. The payments card
  stays where it was.
- PWA: a checkbox «Зведення сум — унизу, після позицій» at the bottom of the share sheet (all three modes),
  off by default, saved on tap (`usePortalSummaryPlacement`, outbox entity `portalSummary`, coalesced).
- Tests: `PortalSummaryPlacementIntegrationTest` (the flag reaches a sent `?t=` page both ways; the page
  renders the block once, after the sections and before the photos), `SharePortalSheet.test` (+2).
