-- =================================================================================================
-- V203 — a tiling position's layout (iteration-tile-layout, stage 2).
--
-- The PWA lays the position's surfaces out on the phone (src/lib/tileLayout.ts) and stores what the
-- master accepted: the tile format, how many m² of the position the layout covers, the tile count as a
-- range plus the spare, and the inputs (JSON) so «Перерахувати» reopens the same layout. The calculator
-- then buys the laid part in whole tiles with no allowance on top, and the rest of the position by area
-- as before. Nothing here is a norm and nothing is shared: one row per estimate line.
--
-- Beside estimate_material_param, not in it: that table holds ONE number per (line, question), and a
-- layout is a document. Like it, untouched by the estimate's @Version and by a signature — what is
-- bought is not the document the client signed.
--
-- The format is stored as its two sides, short first, the way the library names it (300×600); the
-- orientation lives in the JSON. A line renamed to ANOTHER format pauses its layout on the read path
-- (the calculator ignores it and says so) — the row is kept, so «перерахувати» starts from it.
-- =================================================================================================

CREATE TABLE estimate_tile_layout (
    id                     UUID PRIMARY KEY,
    estimate_id            UUID          NOT NULL REFERENCES estimates (id) ON DELETE CASCADE,
    estimate_item_id       UUID          NOT NULL REFERENCES estimate_items (id) ON DELETE CASCADE,
    tile_short_mm          INTEGER       NOT NULL CHECK (tile_short_mm BETWEEN 20 AND 3600),
    tile_long_mm           INTEGER       NOT NULL CHECK (tile_long_mm BETWEEN 20 AND 3600),
    laid_area              NUMERIC(10, 3) NOT NULL CHECK (laid_area > 0 AND laid_area <= 10000),
    min_tiles              INTEGER       NOT NULL CHECK (min_tiles > 0),
    max_tiles              INTEGER       NOT NULL CHECK (max_tiles <= 1000000),
    spare                  INTEGER       NOT NULL CHECK (spare BETWEEN 0 AND 100000),
    params                 TEXT          NOT NULL CHECK (length(params) <= 32768),
    measurements_declined  BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_tile_layout_format CHECK (tile_short_mm <= tile_long_mm),
    CONSTRAINT ck_tile_layout_range CHECK (min_tiles <= max_tiles),
    CONSTRAINT ux_tile_layout_item UNIQUE (estimate_item_id)
);

CREATE INDEX idx_tile_layout_estimate ON estimate_tile_layout (estimate_id);

COMMENT ON TABLE estimate_tile_layout IS
    'A tiling line''s layout (V203): what the calculator buys in whole tiles for laid_area m² of the line; the rest stays by area.';
COMMENT ON COLUMN estimate_tile_layout.measurements_declined IS
    'The master said no to saving these sizes into the object''s measurements — asked once per line.';
