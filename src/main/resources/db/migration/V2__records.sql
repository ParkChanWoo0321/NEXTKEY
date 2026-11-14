CREATE TABLE location_consent (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    granted BOOLEAN NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    sequence_no BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
    FOREIGN KEY (session_id) REFERENCES rental_session(id),
    INDEX consent_session_seq(session_id, sequence_no)
) ENGINE=InnoDB;

CREATE TABLE vehicle_event (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    source VARCHAR(24) NOT NULL,
    verification VARCHAR(30) NOT NULL,
    status VARCHAR(40) NOT NULL,
    consent_id VARCHAR(64),
    payload JSON NOT NULL,
    request_hash CHAR(64) NOT NULL,
    CHECK (kind IN ('LOCATION','MEASUREMENT','WARNING','GAP','SYNC')),
    CHECK (kind='LOCATION' OR consent_id IS NULL),
    CHECK (kind<>'LOCATION' OR status<>'VALID' OR consent_id IS NOT NULL),
    FOREIGN KEY (session_id) REFERENCES rental_session(id),
    FOREIGN KEY (consent_id) REFERENCES location_consent(id),
    INDEX event_session_time(session_id, collected_at, id)
) ENGINE=InnoDB;

CREATE TABLE vehicle_setting_capability (
    vehicle_id VARCHAR(64) NOT NULL,
    setting_name VARCHAR(32) NOT NULL,
    allowed_values JSON NOT NULL,
    CHECK (setting_name IN ('CLIMATE_TARGET_C','CLIMATE_FAN_LEVEL','SEAT_MEMORY_SLOT')),
    PRIMARY KEY(vehicle_id, setting_name),
    FOREIGN KEY(vehicle_id) REFERENCES vehicle(id)
) ENGINE=InnoDB;

CREATE TABLE setting_profile (
    account_id VARCHAR(64) NOT NULL,
    compatibility_key VARCHAR(128) NOT NULL,
    source_vehicle_id VARCHAR(64) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    source VARCHAR(24) NOT NULL,
    readings JSON NOT NULL,
    PRIMARY KEY(account_id, compatibility_key),
    FOREIGN KEY(account_id) REFERENCES account(id),
    FOREIGN KEY(source_vehicle_id) REFERENCES vehicle(id)
) ENGINE=InnoDB;

CREATE TABLE setting_activity (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    state VARCHAR(24) NOT NULL,
    detail JSON NOT NULL,
    FOREIGN KEY(session_id) REFERENCES rental_session(id),
    INDEX activity_session(session_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE damage_report (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    vehicle_id VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    description VARCHAR(1200) NOT NULL,
    discovered_at DATETIME(6) NOT NULL,
    reported_at DATETIME(6) NOT NULL,
    verification VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED',
    verified_by VARCHAR(64),
    verified_at DATETIME(6),
    CHECK (verification IN ('UNVERIFIED','CONFIRMED','DISMISSED')),
    FOREIGN KEY(session_id) REFERENCES rental_session(id),
    FOREIGN KEY(vehicle_id) REFERENCES vehicle(id),
    FOREIGN KEY(verified_by) REFERENCES account(id),
    INDEX damage_vehicle(vehicle_id,reported_at),
    INDEX damage_session(session_id)
) ENGINE=InnoDB;

CREATE TABLE damage_photo (
    id VARCHAR(64) PRIMARY KEY,
    report_id VARCHAR(64) NOT NULL,
    image_bytes MEDIUMBLOB NOT NULL,
    mime_type VARCHAR(24) NOT NULL,
    CHECK (mime_type IN ('image/jpeg','image/png')),
    FOREIGN KEY(report_id) REFERENCES damage_report(id),
    INDEX photo_report(report_id)
) ENGINE=InnoDB;

CREATE TABLE record_request (
    session_id VARCHAR(64) NOT NULL,
    operation_name VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result JSON NOT NULL,
    PRIMARY KEY(session_id, operation_name, request_id),
    FOREIGN KEY(session_id) REFERENCES rental_session(id)
) ENGINE=InnoDB;
