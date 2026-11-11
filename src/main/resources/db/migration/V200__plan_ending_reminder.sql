-- When the master was last told that his paid plan ends (PlanEndingReminderService): three days
-- before and one day before, so the column says which of the two has already gone out.
ALTER TABLE users ADD COLUMN plan_ending_reminded_at TIMESTAMPTZ;
