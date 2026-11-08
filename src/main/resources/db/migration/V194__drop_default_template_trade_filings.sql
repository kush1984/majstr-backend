-- A master could re-file a SYSTEM default into any trade (V64), one tap in the template preview with
-- no confirmation, among every trade the system has — so a tiling bundle landed under a trade the
-- master never picked. A default now always lives under the trade it ships for; the per-master
-- filings are dropped, which puts every re-filed default back where it shipped.
-- The table is left in place, unused, so the instance still serving during the deploy keeps working;
-- a later migration drops it (docs/open-questions.md).
DELETE FROM template_trade_override;
