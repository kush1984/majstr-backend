-- Document requisites (acts iteration, Prompt 2) — the legal/bank details an «Акт виконаних
-- робіт» PDF needs, which the model had nowhere to hold. All nullable except the two enum flags,
-- which carry a safe default so every existing row stays valid without a data backfill.

-- ---- Contractor (users) -----------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN legal_name        varchar(255),  -- ПІБ ФОП / повна назва юрособи (fallback: company_name → full_name)
    ADD COLUMN tax_id            varchar(20),   -- РНОКПП (individual tax number), NOT the VAT id
    ADD COLUMN legal_address     varchar(512),
    ADD COLUMN iban              varchar(64),
    ADD COLUMN bank_name         varchar(255),
    ADD COLUMN vat_payer         boolean       NOT NULL DEFAULT false,
    ADD COLUMN vat_id            varchar(20),   -- ІПН платника ПДВ (VAT payer number) — distinct from tax_id
    ADD COLUMN tax_group         smallint,      -- єдиний податок: 2 / 3 (null = not on the simplified system, or unset)
    ADD COLUMN tax_rate          numeric(5, 2), -- % ставка єдиного податку
    ADD COLUMN doc_city          varchar(120),  -- місто складання документів (blank → the city line is simply omitted)
    ADD COLUMN act_number_format varchar(20)   NOT NULL DEFAULT 'PLAIN';

ALTER TABLE users
    ADD CONSTRAINT users_act_number_format_chk
        CHECK (act_number_format IN ('PLAIN', 'WITH_YEAR'));

-- ---- Customer (clients) -----------------------------------------------------------------------
ALTER TABLE clients
    ADD COLUMN client_type     varchar(20)  NOT NULL DEFAULT 'PERSON', -- PERSON | FOP | COMPANY
    ADD COLUMN tax_id          varchar(20),   -- РНОКПП (FOP) / ЄДРПОУ (company)
    ADD COLUMN legal_name      varchar(255),  -- повна назва (fallback: full_name)
    ADD COLUMN legal_address   varchar(512),
    ADD COLUMN signatory_title varchar(120),  -- посада підписанта (e.g. «Директор»)
    ADD COLUMN signatory_name  varchar(255);  -- ПІБ підписанта

ALTER TABLE clients
    ADD CONSTRAINT clients_client_type_chk
        CHECK (client_type IN ('PERSON', 'FOP', 'COMPANY'));
