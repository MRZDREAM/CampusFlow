CREATE TABLE booking_requests (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    slot_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    reservation_id BIGINT NULL,
    error_code VARCHAR(64) NULL,
    deadline DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    pending_user BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN user_id END) STORED,
    CONSTRAINT uq_booking_request_pending UNIQUE (slot_id, pending_user),
    CONSTRAINT fk_booking_request_slot FOREIGN KEY (slot_id) REFERENCES resource_slots(id),
    CONSTRAINT fk_booking_request_user FOREIGN KEY (user_id) REFERENCES campus_users(id),
    CONSTRAINT fk_booking_request_order FOREIGN KEY (reservation_id) REFERENCES reservation_orders(id),
    CONSTRAINT chk_booking_request_result CHECK (
        (status = 'PENDING' AND reservation_id IS NULL AND error_code IS NULL) OR
        (status = 'SUCCEEDED' AND reservation_id IS NOT NULL AND error_code IS NULL) OR
        (status IN ('REJECTED', 'FAILED') AND reservation_id IS NULL AND error_code IS NOT NULL)
    ),
    INDEX idx_booking_request_deadline (status, deadline)
) ENGINE = InnoDB;

CREATE TABLE booking_outbox (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    attempts INT NOT NULL DEFAULT 0,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    claim_until DATETIME(6) NULL,
    sent_at DATETIME(6) NULL,
    CONSTRAINT fk_booking_outbox_request FOREIGN KEY (request_id) REFERENCES booking_requests(id),
    INDEX idx_booking_outbox_pending (sent_at, available_at)
) ENGINE = InnoDB;
