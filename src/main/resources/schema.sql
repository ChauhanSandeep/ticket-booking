CREATE SEQUENCE IF NOT EXISTS SEAT_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    location VARCHAR(255) NOT NULL,
    event_date TIMESTAMP NOT NULL,
    total_seats INT NOT NULL CHECK (total_seats > 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS seat_holds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hold_id UUID NOT NULL,
    event_id BIGINT NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    active_hold_key VARCHAR(255),
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_hold_id UNIQUE (hold_id),
    CONSTRAINT uq_active_hold_per_user_event UNIQUE (event_id, active_hold_key),
    CONSTRAINT fk_hold_event FOREIGN KEY (event_id) REFERENCES events(id)
);

CREATE TABLE IF NOT EXISTS bookings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_reference UUID NOT NULL,
    event_id BIGINT NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    hold_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    canceled_at TIMESTAMP,
    CONSTRAINT uq_booking_ref UNIQUE (booking_reference),
    CONSTRAINT fk_booking_event FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT fk_booking_hold FOREIGN KEY (hold_id) REFERENCES seat_holds(id)
);

CREATE TABLE IF NOT EXISTS seats (
    id BIGINT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    seat_number VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    hold_id BIGINT,
    booking_id BIGINT,
    updated_at TIMESTAMP,
    CONSTRAINT uq_event_seat UNIQUE (event_id, seat_number),
    CONSTRAINT fk_seat_event FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT fk_seat_hold FOREIGN KEY (hold_id) REFERENCES seat_holds(id),
    CONSTRAINT fk_seat_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
);

CREATE INDEX IF NOT EXISTS idx_seat_event_status ON seats(event_id, status);
CREATE INDEX IF NOT EXISTS idx_seat_hold ON seats(hold_id);
CREATE INDEX IF NOT EXISTS idx_seat_booking ON seats(booking_id);
CREATE INDEX IF NOT EXISTS idx_hold_event_status ON seat_holds(event_id, status);
CREATE INDEX IF NOT EXISTS idx_hold_expires ON seat_holds(expires_at, status);
CREATE INDEX IF NOT EXISTS idx_booking_event_status ON bookings(event_id, status);
