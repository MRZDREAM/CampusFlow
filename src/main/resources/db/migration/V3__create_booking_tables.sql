CREATE TABLE campus_users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_campus_users_username UNIQUE (username),
    CONSTRAINT chk_campus_users_role CHECK (role IN ('USER', 'ADMIN'))
) ENGINE = InnoDB;

CREATE TABLE reservation_orders (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    slot_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CONFIRMED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    cancelled_at DATETIME(6) NULL,
    active_user BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'CONFIRMED' THEN user_id END) STORED,
    CONSTRAINT uq_reservation_active_user UNIQUE (slot_id, active_user),
    CONSTRAINT fk_reservation_slot FOREIGN KEY (slot_id) REFERENCES resource_slots(id),
    CONSTRAINT fk_reservation_user FOREIGN KEY (user_id) REFERENCES campus_users(id),
    CONSTRAINT chk_reservation_status CHECK (
        (status = 'CONFIRMED' AND cancelled_at IS NULL) OR
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
    ),
    INDEX idx_reservation_user_id (user_id, id)
) ENGINE = InnoDB;

CREATE TABLE waitlist_entries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    slot_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WAITING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    offer_expires_at DATETIME(6) NULL,
    reservation_id BIGINT NULL,
    active_user BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('WAITING', 'OFFERED') THEN user_id END) STORED,
    CONSTRAINT uq_waitlist_active_user UNIQUE (slot_id, active_user),
    CONSTRAINT fk_waitlist_slot FOREIGN KEY (slot_id) REFERENCES resource_slots(id),
    CONSTRAINT fk_waitlist_user FOREIGN KEY (user_id) REFERENCES campus_users(id),
    CONSTRAINT fk_waitlist_reservation FOREIGN KEY (reservation_id) REFERENCES reservation_orders(id),
    CONSTRAINT chk_waitlist_status CHECK (status IN ('WAITING', 'OFFERED', 'ACCEPTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT chk_waitlist_offer CHECK (status <> 'OFFERED' OR offer_expires_at IS NOT NULL),
    CONSTRAINT chk_waitlist_reservation CHECK (
        (status = 'ACCEPTED' AND reservation_id IS NOT NULL) OR
        (status <> 'ACCEPTED' AND reservation_id IS NULL)
    ),
    INDEX idx_waitlist_fifo (slot_id, status, id),
    INDEX idx_waitlist_expiry (status, offer_expires_at, slot_id),
    INDEX idx_waitlist_user_id (user_id, id)
) ENGINE = InnoDB;
