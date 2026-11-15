-- Keep V2 capability rows for compatibility; metadata overrides them per field.
CREATE TABLE setting_capability_detail (
    vehicle_id VARCHAR(64) NOT NULL,
    setting_name VARCHAR(32) NOT NULL,
    detail JSON NOT NULL,
    PRIMARY KEY (vehicle_id,setting_name),
    CONSTRAINT fk_setting_capability_detail_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CONSTRAINT ck_setting_capability_detail_name CHECK (setting_name IN
        ('CLIMATE_TARGET_C','CLIMATE_FAN_LEVEL','SEAT_MEMORY_SLOT','SEAT_FORE_AFT_POSITION'))
);
ALTER TABLE setting_profile
    ADD COLUMN observation_details JSON NULL,
    ADD COLUMN memory_reference JSON NULL;
