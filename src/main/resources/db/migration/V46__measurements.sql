-- Object measurements (Заміри): per-object rooms + measured elements, and the two
-- estimate_items columns that remember a line's measurement selection. Full model at
-- once (rooms + items + estimate refs) so we never migrate twice. Owner isolation is
-- via the object (project) in the service; cascade drops rooms/items with the object.

CREATE TABLE measurement_room (
    id          UUID PRIMARY KEY,
    project_id  UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_measurement_room_project ON measurement_room(project_id);

CREATE TABLE measurement_item (
    id          UUID PRIMARY KEY,
    room_id     UUID NOT NULL REFERENCES measurement_room(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    type        VARCHAR(20) NOT NULL,
    unit        VARCHAR(20) NOT NULL,
    result      NUMERIC(15,3) NOT NULL,
    payload     TEXT NOT NULL,          -- raw entered data as JSON (for re-editing)
    sort_order  INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT measurement_item_type_check CHECK (type IN ('SURFACE', 'PARTITION', 'LINEAR')),
    CONSTRAINT measurement_item_unit_check CHECK (unit IN ('M2', 'LINEAR_METER'))
);
CREATE INDEX idx_measurement_item_room ON measurement_item(room_id);

-- A line's measurement selection: which elements were summed into its quantity, and
-- whether the quantity was later edited by hand (then the dialog won't silently overwrite).
ALTER TABLE estimate_items ADD COLUMN measurement_refs TEXT;
ALTER TABLE estimate_items ADD COLUMN quantity_manual  BOOLEAN NOT NULL DEFAULT FALSE;
