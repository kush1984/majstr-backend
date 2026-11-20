-- =================================================================================================
-- V217 — the client pays the master from the portal, and tells him so (iteration-client-pay)
--
-- 1. Where the client pays. The master's own payment details, shown on every portal of his
--    (signature, economy, act, single estimate) behind one «Оплатити» button. Bank-neutral, and the
--    money never passes through us: a card number of any bank, the IBAN that already sits in his
--    document requisites (V103, turned into an NBU payment QR together with the РНОКПП), and any
--    payment link he has (his bank's, LiqPay, WayForPay, ...). All optional; nothing filled = no
--    button.
--
-- 2. «Я оплатив». A transfer can take a day to land, so the master wants the client's word and his
--    receipt meanwhile. The notice is a project message (the inbox, the push and the 6-month
--    attachment retention already exist for those) marked PAYMENT and carrying the amount the
--    client says he sent.
--
-- 3. The receipt is CHECKED, and the money is recorded without the master's tap when it matches
--    (owner ruling: a master with notifications off must still see the payment). The receipt is read
--    (amount, recipient IBAN or card's last four, date, status) and compared with what the client
--    typed and with the master's own details. Matched = a payment_receipt row is written through the
--    one payment door and linked here (RECORDED). Anything else waits in the object's money for the
--    master's «Кошти прийшли» / «Не надійшло» (PENDING → RECORDED | DISMISSED), with the reason.
--    A matching receipt proves the client SENT the money, not that it arrived: the note says so.
-- =================================================================================================

ALTER TABLE users
    ADD COLUMN payout_card varchar(19),
    ADD COLUMN payout_link varchar(500);

ALTER TABLE project_messages
    ADD COLUMN kind varchar(20) NOT NULL DEFAULT 'MESSAGE',
    ADD COLUMN payment_amount numeric(15, 2);

ALTER TABLE project_messages
    ADD CONSTRAINT project_messages_kind_check CHECK (kind IN ('MESSAGE', 'PAYMENT')),
    -- A payment notice always names its amount; an ordinary message never carries one.
    ADD CONSTRAINT project_messages_payment_amount_check
        CHECK ((kind = 'PAYMENT') = (payment_amount IS NOT NULL) AND (payment_amount IS NULL OR payment_amount > 0));

ALTER TABLE project_messages
    ADD COLUMN payment_status varchar(20),
    -- Why a notice waits for the master, or what matched — shown to him as is (Ukrainian).
    ADD COLUMN payment_note varchar(300),
    -- The money this notice put into the object. A payment the master deletes leaves the notice.
    ADD COLUMN payment_receipt_id uuid REFERENCES payment_receipt (id) ON DELETE SET NULL;

ALTER TABLE project_messages
    ADD CONSTRAINT project_messages_payment_status_check
        CHECK (payment_status IN ('PENDING', 'RECORDED', 'DISMISSED')),
    -- Every payment notice is in exactly one state; an ordinary message is in none.
    ADD CONSTRAINT project_messages_payment_status_kind_check
        CHECK ((kind = 'PAYMENT') = (payment_status IS NOT NULL));

-- ON DELETE SET NULL looks the referencing rows up on every payment delete.
CREATE INDEX ix_project_messages_payment_receipt
    ON project_messages (payment_receipt_id) WHERE payment_receipt_id IS NOT NULL;
