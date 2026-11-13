-- =================================================================================================
-- V204 — a master's own position name, counted as a library position (iteration-tile-layout).
--
-- The calculator finds norms by the position's NAME. A master who names a job his own way — typed, or
-- brought in by «Перенести в Majstr» («Фарбування стін за 2 рази», «Шпаклівка стін») — got nothing
-- counted and was not told why. The materials screen now asks once, when one library position of the
-- same unit is close: «Це те саме, що …?». His «так» is remembered here, for that wording, in every
-- estimate of his; `as_name_key` NULL is his «ні, це інше» — his own work, whose materials we do not
-- count (he adds them by hand). The line then stops being asked.
--
-- Keyed by the master's wording (the calculator's NameKeys form) and unit, never by an estimate line, so
-- one answer serves every line he ever writes that way. A library position renamed later leaves the row
-- pointing at a key with no norms — the line is then asked again, never priced silently at nothing.
-- =================================================================================================

CREATE TABLE material_norm_alias (
    id           UUID PRIMARY KEY,
    owner_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name_key     VARCHAR(255) NOT NULL,
    unit         VARCHAR(20)  NOT NULL,
    as_name_key  VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_material_norm_alias UNIQUE (owner_id, name_key, unit)
);

COMMENT ON COLUMN material_norm_alias.as_name_key IS
    'The library position the master counts this wording as; NULL = «не потребує матеріалів» (V204).';
