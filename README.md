# Ticket Booking System

A backend API for booking event tickets with a hold-then-confirm reservation pattern. Built with Spring Boot and H2 in-memory database.

## Features

- Event management (CRUD)
- Temporary seat reservation (hold) on specific seats with 5-minute expiry
- Booking confirmation from held seats
- Automatic cleanup of expired holds (scheduled every 60 seconds)
- Seat availability tracking with per-seat status breakdown
- Soft deletes for booking cancellations (audit trail)
- Pessimistic locking for concurrent booking safety

## Tech Stack

- Java 17
- Spring Boot 3.4
- Spring Data JPA (Hibernate)
- H2 In-Memory Database
- Gradle

## Getting Started

### Prerequisites

- Java 17+
- Docker (optional)

### Run Locally

```bash
./gradlew bootRun
```

The application starts on `http://localhost:8080`.
Two sample events are pre-loaded via `data.sql`.

### Run with Docker

```bash
docker-compose up --build
```

### Run Tests

```bash
./gradlew clean test
```

### H2 Console

Available at `http://localhost:8080/h2-console` when running locally.

- JDBC URL: `jdbc:h2:mem:ticketdb`
- Username: `sa`
- Password: (empty)

## Swagger UI

Interactive API documentation is available at `http://localhost:8080/swagger-ui.html` when the application is running.

OpenAPI spec: `http://localhost:8080/v3/api-docs`

## API Reference

### Events

#### Create Event
```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Spring Concert",
    "description": "Annual spring concert",
    "location": "San Francisco, CA",
    "eventDate": "2026-08-15T19:00:00",
    "totalSeats": 100
  }'
```

#### List Events
```bash
curl http://localhost:8080/api/v1/events
```

#### Get Event
```bash
curl http://localhost:8080/api/v1/events/1
```

#### Update Event
```bash
curl -X PUT http://localhost:8080/api/v1/events/1 \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Spring Concert 2026",
    "location": "San Francisco, CA",
    "eventDate": "2026-08-15T19:00:00"
  }'
```

#### Delete Event
```bash
curl -X DELETE http://localhost:8080/api/v1/events/1
```

#### Get Availability (with per-seat breakdown)
```bash
curl http://localhost:8080/api/v1/events/1/availability
```

### Holds

#### Hold Specific Seats (5-minute reservation)
```bash
curl -X POST http://localhost:8080/api/v1/events/1/holds \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "seatNumbers": ["1", "2", "3"]
  }'
```

#### Get Hold Status
```bash
curl http://localhost:8080/api/v1/holds/{holdId}
```

### Bookings

#### Confirm Booking (from hold)
```bash
curl -X POST http://localhost:8080/api/v1/bookings \
  -H "Content-Type: application/json" \
  -d '{
    "holdId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  }'
```

#### Get Booking
```bash
curl http://localhost:8080/api/v1/bookings/{bookingReference}
```

#### Get User's Bookings
```bash
curl http://localhost:8080/api/v1/users/user-123/bookings
```

#### Cancel Booking (soft delete)
```bash
curl -X PATCH http://localhost:8080/api/v1/bookings/{bookingReference}/cancel
```

## Architecture

### Concurrency Strategy

The system uses **pessimistic locking** (`SELECT ... FOR UPDATE`) on the event row to serialize all seat-modifying operations per event. This prevents overbooking without requiring retry loops.

- Hold creation, booking confirmation, cancellation, and expired hold cleanup all acquire the same lock
- Different events can be processed concurrently (lock is per-event)
- Optimistic locking (`@Version`) is used separately for event CRUD updates

### Hold-Then-Confirm Pattern

1. User requests a hold on specific seats -> seats marked as HELD for 5 minutes
2. User confirms the hold -> seats transition from HELD to BOOKED
3. If not confirmed within 5 minutes, a scheduled cleanup releases the seats

### Database Schema

- `events` - Event details with optimistic locking version
- `seats` - Individual seat rows (one per physical seat) with AVAILABLE/HELD/BOOKED status
- `seat_holds` - Hold records with UUID public identifiers and expiry timestamps
- `bookings` - Booking records with soft delete support (CONFIRMED/CANCELED status)
