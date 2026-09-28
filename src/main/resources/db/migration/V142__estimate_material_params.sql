-- =================================================================================================
-- V142 — the three figures the material calculator ASKS for, kept on the estimate, not on a device.
--
-- WHAT WAS WRONG
--   The calculation itself stores nothing (V127 — the GET recomputes, the POST carries what the
--   master left on the screen) and that stays true. But three of its inputs are not derived from
--   anything: a room's PERIMETER (V127), a короб's розгортка (SECTION, V131) and a layer's
--   THICKNESS in millimetres (V137) are ANSWERS the master types. They lived in `localStorage`
--   under `materials:<estimateId>:params`, which is per-device and per-browser: he answered 5 mm on
--   the phone, opened the same estimate on his laptop and the same card asked the same question
--   with our `default_param` back in the field — two shopping lists off one estimate, and nothing
--   on either screen saying they disagree.
--
-- WHY ITS OWN TABLE, AND NOT A COLUMN ON `estimate_items`
--   1. V137's own rule: ONE position can be asked BOTH a SECTION and a THICKNESS, which is exactly
--      why the two ride as two separate parameters and never as one shared map. A single
--      `material_param` column could not hold both, and would answer one question with the other's
--      number — the bug V137 was written to avoid.
--   2. `estimates` carries `@Version` (V23), and V141 sends that version into the portal render and
--      back on the client's sign request (409 ESTIMATE_CHANGED). A master answering a thickness on
--      his own calculator must not invalidate a signature the client is in the middle of giving.
--      A side table touches nothing the document has a version of, and needs no `requireNotSigned`:
--      it is a scratchpad about the work, not a line of the document.
--   3. A fifth NormBasis then costs no migration on the two hottest tables in the schema.
--
-- THE PERIMETER HAS NO POSITION
--   One room, one perimeter (V127), so its row carries `estimate_item_id IS NULL`. The CHECK pairs
--   the basis with the presence of a position, so nothing can file a thickness with no line or a
--   perimeter against one. Two PARTIAL unique indexes rather than one over a COALESCE: they state
--   the two rules in the two sentences they actually are.
--
-- ZERO IS NOT AN ANSWER
--   `value > 0`. The calculator already treats a non-positive parameter as MISSING and asks again,
--   so a field the master clears DELETES the row instead of storing 0 — one representation of «he
--   has not answered», never two. The upper bound is the same 1000 the service and the PWA's own
--   field guard use: it is a stray extra digit it looks for, not a rule of building.
-- =================================================================================================

CREATE TABLE estimate_material_param (
    id               uuid PRIMARY KEY,
    estimate_id      uuid NOT NULL REFERENCES estimates (id) ON DELETE CASCADE,
    -- NULL exactly for PERIMETER; the CHECK below is what keeps that exact.
    estimate_item_id uuid REFERENCES estimate_items (id) ON DELETE CASCADE,
    basis            varchar(20) NOT NULL,
    value            numeric(12, 3) NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT estimate_material_param_basis CHECK (basis IN ('PERIMETER', 'SECTION', 'THICKNESS')),
    CONSTRAINT estimate_material_param_value CHECK (value > 0 AND value <= 1000),
    CONSTRAINT estimate_material_param_position CHECK (
        (basis = 'PERIMETER' AND estimate_item_id IS NULL)
        OR (basis <> 'PERIMETER' AND estimate_item_id IS NOT NULL))
);

-- One answer per (position, question) ...
CREATE UNIQUE INDEX ux_estimate_material_param_position
    ON estimate_material_param (estimate_item_id, basis)
    WHERE estimate_item_id IS NOT NULL;
-- ... and one per (estimate, question) for the one question no position owns.
CREATE UNIQUE INDEX ux_estimate_material_param_estimate
    ON estimate_material_param (estimate_id, basis)
    WHERE estimate_item_id IS NULL;

-- The read path asks for one estimate's whole set, once per calculation.
CREATE INDEX idx_estimate_material_param_estimate ON estimate_material_param (estimate_id);
