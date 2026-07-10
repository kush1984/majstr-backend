-- Which device a master last used, parsed from the User-Agent on each
-- authenticated request (throttled together with last_active_at).
-- device_type = MOBILE / TABLET / DESKTOP / UNKNOWN; os is a short label
-- (iOS / Android / Windows / macOS / Linux / ChromeOS). Browser is not tracked.
ALTER TABLE users ADD COLUMN last_device_type VARCHAR(20);
ALTER TABLE users ADD COLUMN last_os VARCHAR(40);
