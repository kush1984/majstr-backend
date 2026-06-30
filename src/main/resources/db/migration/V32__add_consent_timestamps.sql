-- Consent timestamps for the privacy / consent feature.
--   consented_to_privacy_at     — when the master agreed to the Privacy Policy
--                                 (registration checkbox, or the one-time login
--                                 modal for users who registered before it existed).
--   acknowledged_client_data_at — when the master confirmed responsibility for
--                                 entering client data (the controller/operator
--                                 distinction). Shown once, on first client data.
-- Both nullable: existing users keep NULL → the PWA shows the matching prompt
-- (login consent modal / client-data acknowledgement) on next relevant action.
ALTER TABLE users ADD COLUMN consented_to_privacy_at     TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN acknowledged_client_data_at TIMESTAMPTZ;
