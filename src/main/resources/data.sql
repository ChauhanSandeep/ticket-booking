-- Sample event data for demonstration
INSERT INTO events (name, description, location, event_date, total_seats, version, created_at, updated_at)
VALUES ('Spring Tech Conference 2026', 'Annual spring technology conference', 'San Francisco, CA', '2026-06-15 09:00:00', 20, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO events (name, description, location, event_date, total_seats, version, created_at, updated_at)
VALUES ('Summer Music Festival', 'Three-day outdoor music festival', 'Austin, TX', '2026-07-20 14:00:00', 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Generate seats for Spring Tech Conference (event_id = 1, 20 seats)
INSERT INTO seats (id, event_id, seat_number, status, updated_at)
SELECT NEXT VALUE FOR SEAT_SEQ, 1, CAST(x AS VARCHAR), 'AVAILABLE', CURRENT_TIMESTAMP
FROM SYSTEM_RANGE(1, 20);

-- Generate seats for Summer Music Festival (event_id = 2, 10 seats)
INSERT INTO seats (id, event_id, seat_number, status, updated_at)
SELECT NEXT VALUE FOR SEAT_SEQ, 2, CAST(x AS VARCHAR), 'AVAILABLE', CURRENT_TIMESTAMP
FROM SYSTEM_RANGE(1, 10);
