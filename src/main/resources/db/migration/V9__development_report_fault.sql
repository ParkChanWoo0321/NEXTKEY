-- An opt-in, generated development fixture only. Production has no service that arms these rows.
CREATE TABLE development_report_fault (
    session_id VARCHAR(64) PRIMARY KEY,
    state VARCHAR(12) NOT NULL,
    armed_at DATETIME(6) NOT NULL,
    failed_at DATETIME(6),
    cleared_at DATETIME(6),
    CONSTRAINT chk_development_report_fault_state CHECK (state IN ('ARMED','FAILED','CLEARED')),
    CONSTRAINT fk_development_report_fault_session FOREIGN KEY (session_id) REFERENCES rental_session(id)
) ENGINE=InnoDB;
