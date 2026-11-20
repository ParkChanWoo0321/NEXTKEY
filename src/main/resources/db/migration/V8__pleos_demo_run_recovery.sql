CREATE TABLE pleos_demo_run (
    run_id CHAR(36) PRIMARY KEY,
    presenter_id VARCHAR(64) NOT NULL,
    reservation_id VARCHAR(64) NOT NULL,
    prepared_end_at DATETIME(6) NOT NULL,
    overdue_at DATETIME(6),
    reset_to_run CHAR(36),
    reset_digest CHAR(64),
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (presenter_id) REFERENCES account(id),
    FOREIGN KEY (reservation_id) REFERENCES reservation(id),
    FOREIGN KEY (reset_to_run) REFERENCES pleos_demo_run(run_id),
    CHECK ((reset_to_run IS NULL AND reset_digest IS NULL) OR
           (reset_to_run IS NOT NULL AND reset_digest IS NOT NULL))
) ENGINE=InnoDB;
