CREATE TABLE resources (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_resources_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_resources_status CHECK (status IN ('OPEN', 'CLOSED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE resource_slots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resource_id BIGINT NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    capacity INT NOT NULL,
    reserved INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_resource_slots_resource_start (resource_id, starts_at),
    CONSTRAINT fk_resource_slots_resource FOREIGN KEY (resource_id) REFERENCES resources (id),
    CONSTRAINT chk_resource_slots_window CHECK (ends_at > starts_at),
    CONSTRAINT chk_resource_slots_capacity CHECK (capacity > 0),
    CONSTRAINT chk_resource_slots_reserved CHECK (reserved >= 0 AND reserved <= capacity),
    CONSTRAINT chk_resource_slots_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT chk_resource_slots_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
