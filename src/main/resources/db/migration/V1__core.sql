CREATE TABLE account (
    id VARCHAR(64) PRIMARY KEY,
    role VARCHAR(16) NOT NULL,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_demo BOOLEAN NOT NULL DEFAULT FALSE,
    failed_attempts INT NOT NULL DEFAULT 0,
    blocked_until DATETIME(6),
    factor_attempts INT NOT NULL DEFAULT 0,
    factor_blocked_until DATETIME(6),
    CHECK (role IN ('CUSTOMER','OPERATOR','VEHICLE'))
) ENGINE=InnoDB;

CREATE TABLE vehicle (
    id VARCHAR(64) PRIMARY KEY,
    model VARCHAR(128) NOT NULL,
    energy_type VARCHAR(16) NOT NULL,
    compatibility_key VARCHAR(128) NOT NULL,
    CHECK (energy_type IN ('FUEL','BATTERY'))
) ENGINE=InnoDB;

CREATE TABLE reservation (
    id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    vehicle_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    confirmed_end_at DATETIME(6) NOT NULL,
    destination_json JSON,
    return_json JSON,
    FOREIGN KEY (account_id) REFERENCES account(id),
    FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CHECK (status IN ('CONFIRMED','CANCELLED')),
    CHECK (confirmed_end_at > starts_at),
    UNIQUE KEY reservation_parties (id,account_id,vehicle_id),
    INDEX reservation_account_time (account_id, starts_at),
    INDEX reservation_vehicle_time (vehicle_id, starts_at)
) ENGINE=InnoDB;

CREATE TABLE rental_session (
    id VARCHAR(64) PRIMARY KEY,
    reservation_id VARCHAR(64) NOT NULL UNIQUE,
    account_id VARCHAR(64) NOT NULL,
    vehicle_id VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6),
    start_json JSON NOT NULL,
    end_json JSON,
    cleanup_state VARCHAR(16) NOT NULL DEFAULT 'NOT_READY',
    report_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
    return_state VARCHAR(16) NOT NULL DEFAULT 'UNCONFIRMED',
    occupied_vehicle VARCHAR(64) GENERATED ALWAYS AS
        (CASE WHEN cleanup_state <> 'COMPLETE' THEN vehicle_id END) STORED,
    occupied_account VARCHAR(64) GENERATED ALWAYS AS
        (CASE WHEN cleanup_state <> 'COMPLETE' THEN account_id END) STORED,
    UNIQUE (occupied_vehicle),
    UNIQUE (occupied_account),
    FOREIGN KEY (reservation_id,account_id,vehicle_id) REFERENCES reservation(id,account_id,vehicle_id),
    FOREIGN KEY (account_id) REFERENCES account(id),
    FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CHECK (state IN ('ACTIVE','END_PENDING','ENDED')),
    CHECK (cleanup_state IN ('NOT_READY','PENDING','COMPLETE')),
    CHECK (report_state IN ('NONE','PENDING','READY','FAILED')),
    CHECK (return_state IN ('UNCONFIRMED','CONFIRMED'))
) ENGINE=InnoDB;

CREATE TABLE auth_token (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64),
    expires_at DATETIME(6) NOT NULL,
    dev_issued BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (account_id) REFERENCES account(id),
    FOREIGN KEY (session_id) REFERENCES rental_session(id),
    INDEX token_session (session_id),
    INDEX token_expiry (expires_at)
) ENGINE=InnoDB;

CREATE TABLE auth_challenge (
    id VARCHAR(64) PRIMARY KEY,
    vehicle_id VARCHAR(64) NOT NULL,
    method VARCHAR(8) NOT NULL,
    qr_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    poll_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    second_expires_at DATETIME(6),
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    account_id VARCHAR(64),
    reservation_id VARCHAR(64),
    session_id VARCHAR(64),
    start_input JSON NOT NULL,
    dev_issued BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    FOREIGN KEY (account_id) REFERENCES account(id),
    FOREIGN KEY (session_id) REFERENCES rental_session(id),
    CHECK (method IN ('QR','DIRECT')),
    CHECK (status IN ('OPEN','SECOND_FACTOR','VERIFIED','CLAIMED','SUPERSEDED')),
    INDEX challenge_vehicle (vehicle_id,status)
) ENGINE=InnoDB;
