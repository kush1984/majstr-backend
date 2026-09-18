-- =================================================================================================
-- V135 — «Мої гроші»: the master's OWN cash flow, not any one object's.
--
-- WHAT WAS MISSING
--   Every money row this app holds is object-scoped — `payment_receipt` (money in), `object_expenses`
--   (money out) — and every query over them reads `WHERE object_id = ?`. There is not one owner-wide
--   money query in the codebase. So a master could see each job's economy and never his own month.
--
--   And a real part of his money never had a place at all: fuel, tools, rent, taxes, and income for
--   work that closed without an act («не все переводиться через акти, багато хто так не працює»).
--
-- THE SHAPE, AND THE RULE IT RESTS ON
--   `cash_entry` holds ONLY what the objects do not already know. Money that belongs to an object
--   keeps being written to that object's own journal — the cash screen unions the three sources on
--   the read path. That is deliberate: a personal row that merely NAMED an object would leave the
--   object's economy saying «Отримано 0» while the personal book said otherwise, and two books that
--   disagree is how a master stops trusting both.
--
--   `happened_on` (a DATE) is the authoritative day and `happened_at` only orders rows inside it.
--   Deriving the day from a timestamp would mean deriving it in SOME timezone, and the object rows
--   beside it carry a bare date — this way one day means one day everywhere on the screen.
--
-- `payment_receipt.material_refund`
--   Money the client pays BACK for material the master laid out is not earnings: counting it would
--   inflate a month by exactly the material. `payment_receipt` records no purpose (see
--   docs/open-questions.md), so this flag is the small, honest first half — what the master tags
--   himself is distinguishable, and nothing else changes.
--
--   **It moves no object figure.** No economy query reads it; only the cash-flow screen does. That
--   is the standing constraint on this area: existing clients' numbers must not shift under them.
-- =================================================================================================

CREATE TABLE cash_entry (
    id              uuid PRIMARY KEY,
    owner_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    direction       varchar(10) NOT NULL,
    amount          numeric(15, 2) NOT NULL,
    category        varchar(20),
    note            varchar(500),
    -- The day this money moved. Editable; defaults to today on the client.
    happened_on     date        NOT NULL,
    -- Stamped automatically, editable only if the master goes looking for it. Its whole job is
    -- ordering within a day — a time picker on every entry is friction for nothing else.
    happened_at     timestamptz NOT NULL,
    -- Income the client paid back for material: stays in the cash movement, leaves «Заробив».
    material_refund boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT cash_entry_direction_chk CHECK (direction IN ('INCOME', 'EXPENSE')),
    -- 0 is not an intermediate state here, unlike a photographed receipt: nothing is saved before
    -- the number is known, because there is no paper to protect.
    CONSTRAINT cash_entry_amount_chk CHECK (amount > 0),
    CONSTRAINT cash_entry_category_chk CHECK (category IS NULL OR category IN (
        'MATERIALS', 'CREW', 'FUEL', 'TOOLS', 'TAXES', 'ADVANCE', 'WORK', 'OTHER')),
    -- Only income can be a refund; an expense marked so would be meaningless and would quietly
    -- distort «Заробив» in the other direction.
    CONSTRAINT cash_entry_refund_chk CHECK (material_refund = false OR direction = 'INCOME')
);

-- Every read is «this master, this period», in that order.
CREATE INDEX idx_cash_entry_owner_day ON cash_entry (owner_id, happened_on DESC);

ALTER TABLE payment_receipt ADD COLUMN material_refund boolean NOT NULL DEFAULT false;

-- The promise this migration makes, asserted rather than claimed: it adds the ABILITY to mark a
-- refund and marks nothing. Every existing receipt keeps counting exactly as it did.
DO $$
DECLARE
    flagged bigint;
BEGIN
    SELECT count(*) INTO flagged FROM payment_receipt WHERE material_refund;
    IF flagged <> 0 THEN
        RAISE EXCEPTION 'V135 must leave every existing payment unflagged, but % row(s) came out '
                        'marked as a material refund', flagged;
    END IF;
END $$;
