-- Act receipts, round 2 (master feedback): a receipt whose POSITIONS were recognized and carried
-- into the act as its own lines. Such a receipt stays attached as the photo proof, but its amount
-- must not be billed a second time: the positions already bill it. itemized = true excludes the
-- receipt from «Разом за чеками» / payable, from the ADDENDUM rollup and from the accepted-by-acts
-- axis — everywhere except the expense posting (the master's own spend is real either way).
ALTER TABLE work_act_receipt
    ADD COLUMN itemized boolean NOT NULL DEFAULT false;

-- Whether the PDF carries the «ДОДАТОК: ФОТО ЧЕКІВ» pages (master feedback): default ON, but a
-- master printing a formal act may not want ten photos stapled to it. The receipts MONEY table
-- always renders (it explains «До сплати»), and the portal always shows the photos — this flag is
-- PDF-appendix-only.
ALTER TABLE work_act
    ADD COLUMN show_receipt_photos boolean NOT NULL DEFAULT true;

-- Photo FOLDERS (master feedback): the Фото tab gets two default folders — «Чеки» (the reserved
-- value RECEIPTS) and «Інше» (NULL) — plus any name the master invents. A folder is just a label:
-- it exists while a photo carries it. Receipt photos from ANY flow land in «Чеки»; existing
-- receipt-source photos are backfilled so the new default folders are truthful from day one.
ALTER TABLE project_photo
    ADD COLUMN folder varchar(100);

UPDATE project_photo SET folder = 'RECEIPTS' WHERE source = 'RECEIPT';

-- Custom folders are PERSISTED (master decision: an empty folder must survive — created ahead of
-- the photos it will hold). The two defaults are virtual and never stored: «Чеки» = the reserved
-- RECEIPTS value, «Інше» = NULL. Moving a photo into a new name auto-creates the row; deleting is
-- allowed only when no photo carries the name.
CREATE TABLE project_photo_folder (
    id         uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name       varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT project_photo_folder_uk UNIQUE (project_id, name)
);
