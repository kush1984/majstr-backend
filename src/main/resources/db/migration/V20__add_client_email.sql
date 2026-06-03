-- Optional client email, used to send the estimate portal link by mail.
-- Nullable — a contractor often only has the client's phone.

ALTER TABLE clients ADD COLUMN email VARCHAR(255);
