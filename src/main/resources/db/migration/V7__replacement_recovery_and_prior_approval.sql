ALTER TABLE session_replacement
    ADD COLUMN preapproved_by VARCHAR(64),
    ADD COLUMN preapproved_at DATETIME(6),
    ADD COLUMN preparation_digest CHAR(64),
    ADD COLUMN preparation_result JSON,
    ADD CONSTRAINT ck_replacement_preapproval CHECK (
        (preapproved_by IS NULL AND preapproved_at IS NULL)
        OR (preapproved_by IS NOT NULL AND preapproved_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_replacement_preparation CHECK (
        (preparation_digest IS NULL AND preparation_result IS NULL)
        OR (preparation_digest IS NOT NULL AND preparation_result IS NOT NULL)
    );
