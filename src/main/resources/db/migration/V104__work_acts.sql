-- Work acts (Акти виконаних робіт) — acts iteration, Prompt 3.
-- An act is a document built from a signed estimate's positions, signed separately by the client.
-- Everything is a FROZEN copy (name/category/unit/price): the estimate line can be edited or
-- deleted afterwards, but an act already sent to the client must read identically a year later.

CREATE TABLE work_act (
    id                  uuid PRIMARY KEY,
    user_id             uuid NOT NULL REFERENCES users(id),
    project_id          uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    number              varchar(20) NOT NULL,
    kind                varchar(20) NOT NULL,          -- INTERIM | FINAL
    status              varchar(20) NOT NULL,          -- DRAFT | SENT | SIGNED | REJECTED
    issued_at           date NOT NULL,
    period_from         date NOT NULL,                 -- both mandatory, separate from issued_at
    period_to           date NOT NULL,
    place               varchar(120),
    contract_ref        varchar(255),
    note                text,
    show_materials      boolean NOT NULL DEFAULT true,
    show_cumulative     boolean NOT NULL DEFAULT true,
    advance_offset      numeric(15, 2),
    retention_percent   numeric(5, 2),                 -- reserved, no UI yet
    sent_at             timestamptz,
    signed_at           timestamptz,
    signer_name         varchar(255),
    signer_phone        varchar(50),
    signer_ip           varchar(64),
    signer_user_agent   varchar(512),
    signed_offline      boolean NOT NULL DEFAULT false,
    doc_hash            varchar(64),
    -- The auto-created «Додаткові роботи» estimate, if this act was signed with extra positions.
    addendum_estimate_id uuid REFERENCES estimates(id) ON DELETE SET NULL,
    version             bigint NOT NULL DEFAULT 0,      -- @Version optimistic lock, like Estimate
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT work_act_kind_chk   CHECK (kind IN ('INTERIM', 'FINAL')),
    CONSTRAINT work_act_status_chk CHECK (status IN ('DRAFT', 'SENT', 'SIGNED', 'REJECTED')),
    -- Numbering is continuous per master (gaps from deleted drafts are fine); the display string is
    -- unique per user regardless of PLAIN/WITH_YEAR format.
    CONSTRAINT work_act_user_number_uk UNIQUE (user_id, number)
);

CREATE INDEX idx_work_act_project ON work_act(project_id);

CREATE TABLE work_act_item (
    id                uuid PRIMARY KEY,
    work_act_id       uuid NOT NULL REFERENCES work_act(id) ON DELETE CASCADE,
    -- NULL = an ADDITIONAL work not in any estimate. SET NULL so the frozen act survives the
    -- estimate line being deleted.
    estimate_item_id  uuid REFERENCES estimate_items(id) ON DELETE SET NULL,
    -- Which estimate this line belongs to, for grouping in the PDF (frozen, SET NULL on delete).
    estimate_id       uuid REFERENCES estimates(id) ON DELETE SET NULL,
    type              varchar(20) NOT NULL,            -- ItemType, frozen
    name              varchar(255) NOT NULL,           -- frozen
    category          varchar(100),                    -- frozen; PDF groups by it, as in the estimate
    unit              varchar(20) NOT NULL,            -- Unit, frozen
    unit_price        numeric(15, 2) NOT NULL,         -- frozen
    quantity          numeric(15, 3) NOT NULL,         -- done in THIS act
    line_total        numeric(15, 2) NOT NULL,         -- server-authored, never from a request
    cumulative_before numeric(15, 3) NOT NULL,         -- frozen at act creation, never recomputed
    sort_order        int NOT NULL
);

CREATE INDEX idx_work_act_item_act ON work_act_item(work_act_id);
CREATE INDEX idx_work_act_item_estimate_item ON work_act_item(estimate_item_id);

-- Estimates gain a kind so ADDENDUM rollups can be excluded from the pickers / Кошториси list.
ALTER TABLE estimates
    ADD COLUMN kind varchar(20) NOT NULL DEFAULT 'REGULAR';

ALTER TABLE estimates
    ADD CONSTRAINT estimates_kind_chk CHECK (kind IN ('REGULAR', 'ADDENDUM'));
