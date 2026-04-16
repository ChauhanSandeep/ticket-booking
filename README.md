# Ticket Booking System

A backend API for booking event tickets with a hold-then-confirm reservation pattern. Built with Spring Boot and H2 in-memory database.

## Features

- Event management (CRUD)
- Temporary seat reservation (hold) with 5-minute expiry
- Booking confirmation from held seats
- Automatic cleanup of expired holds
- Seat availability tracking with per-seat status
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
