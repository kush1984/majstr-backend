-- =================================================================================================
-- V195 — the number left on «Залиште телефон — передзвонимо» leaves the documents.
--
-- Between V191 and V193 that card wrote the number into `users.phone`, which documents print. V193
-- copied it into `help_phone` but left `users.phone` alone, so it kept reaching clients. The card was
-- shown then only to a master with NO phone, so a phone equal to `help_phone` on an ask made before
-- V193 is the card's — cleared here (`help_phone` keeps it for the call). An ask made after V193 never
-- touched `users.phone`, so a master who typed the same number into his profile himself keeps it.
-- =================================================================================================

UPDATE users u
SET phone = NULL
WHERE u.help_requested_at IS NOT NULL
  AND u.phone IS NOT NULL
  AND u.phone = u.help_phone
  AND u.help_requested_at < (SELECT h.installed_on FROM flyway_schema_history h WHERE h.version = '193');
