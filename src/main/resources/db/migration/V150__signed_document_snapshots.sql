-- =================================================================================================
-- V150 - what a signature certifies is stored, not re-rendered (review B-76).
--
-- A signed act's doc_hash was SHA-256 of PDF BYTES, and OpenPDF writes a time-based /ID and
-- CreationDate, so no render ever reproduced it. Worse, every later render read LIVE data - the
-- contractor's IBAN and requisites, the logo, the client's and the object's names - so a master who
-- corrected his IBAN changed every act he had ever had signed. A signed estimate had the same live
-- render and no stamp at all.
--
-- From here on a signature stores a canonical JSON snapshot of every printed value. doc_hash is
-- SHA-256 of that exact text (kept verbatim in `snapshot`, never re-serialised, so anyone holding it
-- can recompute the stamp), and the document is rendered FROM the snapshot ever after. The stamped
-- PDF produced at the signature - the one mailed to the client - is kept in storage under `pdf_key`.
--
-- One row per signature. An act is signed once (partial unique index). An estimate can be reopened
-- and signed again; each signature keeps its own row, and `estimates.signed_document_id` points at
-- the CURRENT one (cleared on reopen). A document signed before this migration has no row and keeps
-- rendering live - its stored hash was never reproducible and is left as it is (owner ruling
-- 2026-10-12).
-- =================================================================================================

CREATE TABLE signed_document (
    id              uuid         PRIMARY KEY,
    kind            varchar(16)  NOT NULL,
    work_act_id     uuid         REFERENCES work_act (id) ON DELETE CASCADE,
    estimate_id     uuid         REFERENCES estimates (id) ON DELETE CASCADE,
    schema_version  integer      NOT NULL,
    snapshot        text         NOT NULL,
    doc_hash        varchar(64)  NOT NULL,
    pdf_key         varchar(255),
    logo_key        varchar(255),
    signed_at       timestamptz  NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT signed_document_kind_check CHECK (kind IN ('ACT', 'ESTIMATE')),
    CONSTRAINT signed_document_one_subject CHECK (
        (kind = 'ACT' AND work_act_id IS NOT NULL AND estimate_id IS NULL)
        OR (kind = 'ESTIMATE' AND estimate_id IS NOT NULL AND work_act_id IS NULL))
);

CREATE UNIQUE INDEX ux_signed_document_act ON signed_document (work_act_id) WHERE work_act_id IS NOT NULL;
CREATE INDEX ix_signed_document_estimate ON signed_document (estimate_id) WHERE estimate_id IS NOT NULL;

ALTER TABLE work_act  ADD COLUMN signed_document_id uuid REFERENCES signed_document (id) ON DELETE SET NULL;
ALTER TABLE estimates ADD COLUMN signed_document_id uuid REFERENCES signed_document (id) ON DELETE SET NULL;

COMMENT ON COLUMN signed_document.snapshot IS
    'Canonical JSON of every printed value at the signature, stored verbatim: doc_hash = sha256(snapshot).';
