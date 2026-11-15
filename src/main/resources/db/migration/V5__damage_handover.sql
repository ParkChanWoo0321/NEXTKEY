ALTER TABLE damage_report
    ADD COLUMN part_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN',
    ADD CONSTRAINT chk_damage_part_code CHECK (part_code IN
        ('FRONT','REAR','LEFT_FRONT','RIGHT_FRONT','LEFT_REAR','RIGHT_REAR','HOOD','ROOF','UNKNOWN'));

-- The latest decision remains on damage_report. Preserve its predecessor when a decision changes.
CREATE TABLE damage_verification_history (
    sequence_no BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id VARCHAR(64) NOT NULL,
    verification VARCHAR(16) NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    CHECK (verification IN ('UNVERIFIED','CONFIRMED','DISMISSED')),
    FOREIGN KEY (report_id) REFERENCES damage_report(id),
    INDEX damage_verification_asof (report_id, effective_at, sequence_no)
) ENGINE=InnoDB;
