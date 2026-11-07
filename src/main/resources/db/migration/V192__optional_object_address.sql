-- =================================================================================================
-- V192 — an object's address becomes optional; its NAME stays required (registration friction,
-- round 2). A master starting his first estimate often has no address to hand yet, and «хоч якась
-- назва» is enough to find the object again. The address can be added or changed in the object's
-- edit later; documents print no «Адреса» line while it is empty.
--
-- Schema only. Every existing object keeps its address.
-- =================================================================================================

ALTER TABLE projects ALTER COLUMN address DROP NOT NULL;
