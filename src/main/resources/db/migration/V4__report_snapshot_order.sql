-- Existing reports predate independent snapshot assembly. Their generation time is the known boundary.
ALTER TABLE rental_report ADD COLUMN snapshot_at DATETIME(6);
UPDATE rental_report SET snapshot_at=generated_at WHERE snapshot_at IS NULL;
ALTER TABLE rental_report MODIFY snapshot_at DATETIME(6) NOT NULL;
