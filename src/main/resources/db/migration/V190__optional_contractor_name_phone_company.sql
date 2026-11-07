-- =================================================================================================
-- V190 — a master's name, phone and company become optional (registration friction, round 2).
--
-- Registration now asks for email + password + consent only. The name is asked at the first moment a
-- document leaves for a client (`SendReadiness`), the phone and the company stay optional for good:
-- an empty company prints the name (`ContractorName`), an empty phone prints no phone line.
--
-- Schema only. Every existing row keeps its values; nothing is backfilled or cleared.
-- =================================================================================================

ALTER TABLE users ALTER COLUMN full_name DROP NOT NULL;
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE users ALTER COLUMN company_name DROP NOT NULL;
