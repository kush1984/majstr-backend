-- Act title (master feedback): a real interim act is named after its work stage
-- («Штукатурні роботи», «Шпаклювання»), not just numbered. Optional; frozen by signing like
-- everything else on the act. The PWA suggests names from the object's estimate categories and
-- auto-fills one when every selected line shares a single category.
ALTER TABLE work_act ADD COLUMN title varchar(120);
