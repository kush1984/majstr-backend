ALTER TABLE estimates
    ADD COLUMN signed_at    TIMESTAMPTZ,
    ADD COLUMN signer_name  VARCHAR(255),
    ADD COLUMN signer_phone VARCHAR(50),
    ADD COLUMN signer_ip    VARCHAR(64);
