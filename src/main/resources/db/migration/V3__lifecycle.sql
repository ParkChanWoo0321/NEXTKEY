CREATE TABLE end_request (
    session_id VARCHAR(64) PRIMARY KEY,
    request_id CHAR(36) NOT NULL UNIQUE,
    request_digest CHAR(64) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    confirmed_due_at DATETIME(6) NOT NULL,
    final_safety JSON NOT NULL,
    final_settings JSON NOT NULL,
    pending_event_ids JSON NOT NULL,
    CONSTRAINT fk_end_session FOREIGN KEY(session_id) REFERENCES rental_session(id)
) ENGINE=InnoDB;

CREATE TABLE rental_extension (
    id CHAR(36) PRIMARY KEY,
    reservation_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    previous_end_at DATETIME(6) NOT NULL,
    requested_end_at DATETIME(6) NOT NULL,
    status ENUM('REQUESTED','WAITING_CONSENT','PENDING_APPROVAL','APPROVED','REJECTED') NOT NULL,
    quote_amount DECIMAL(15,2),
    currency CHAR(3),
    quoted_by VARCHAR(64),
    quoted_at DATETIME(6),
    agreed_at DATETIME(6),
    decided_by VARCHAR(64),
    decided_at DATETIME(6),
    decision_note VARCHAR(256),
    active_reservation VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN status IN ('REQUESTED','WAITING_CONSENT','PENDING_APPROVAL') THEN reservation_id END
    ) STORED,
    UNIQUE KEY uq_extension_pending(active_reservation),
    KEY ix_extension_reservation(reservation_id, requested_at),
    CONSTRAINT fk_extension_reservation FOREIGN KEY(reservation_id) REFERENCES reservation(id),
    CONSTRAINT fk_extension_owner FOREIGN KEY(account_id) REFERENCES account(id),
    CONSTRAINT ck_extension_period CHECK (requested_end_at > previous_end_at),
    CONSTRAINT ck_extension_amount CHECK (quote_amount IS NULL OR quote_amount >= 0)
) ENGINE=InnoDB;

CREATE TABLE rental_report (
    session_id VARCHAR(64) PRIMARY KEY,
    generated_at DATETIME(6) NOT NULL,
    payload JSON NOT NULL,
    CONSTRAINT fk_report_session FOREIGN KEY(session_id) REFERENCES rental_session(id)
) ENGINE=InnoDB;

CREATE TABLE session_replacement (
    new_reservation_id VARCHAR(64) PRIMARY KEY,
    old_session_id VARCHAR(64) NOT NULL UNIQUE,
    status ENUM('PENDING','APPROVED') NOT NULL,
    requested_by VARCHAR(64) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    approved_by VARCHAR(64),
    approved_at DATETIME(6),
    CONSTRAINT fk_replacement_session FOREIGN KEY(old_session_id) REFERENCES rental_session(id),
    CONSTRAINT fk_replacement_new FOREIGN KEY(new_reservation_id) REFERENCES reservation(id)
) ENGINE=InnoDB;
