-- A client's phone is optional: a master adds the client on site before he has the number.
ALTER TABLE clients ALTER COLUMN phone DROP NOT NULL;
