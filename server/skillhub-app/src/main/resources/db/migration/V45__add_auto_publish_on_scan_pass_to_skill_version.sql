-- Review-exempt fast path flag: trusted TEAM managers may update a published skill without human
-- review, using the security scan as the publish gate. When true, a SAFE verdict auto-publishes the
-- version; any non-SAFE verdict or scanner failure falls back to the normal PENDING_REVIEW flow.
ALTER TABLE skill_version ADD COLUMN auto_publish_on_scan_pass BOOLEAN NOT NULL DEFAULT FALSE;
