# Runbook

Operational guide for running, testing, and troubleshooting the Ticket Booking System.

## Quick Start

### Prerequisites
- Java 17+ installed (`java -version` to check)
- Docker (optional, for containerized deployment)

### Start the Application

```bash
# Using Gradle wrapper (recommended)
./gradlew bootRun

# Or build and run the JAR
./gradlew bootJar
java -jar build/libs/ticket-booking-0.0.1-SNAPSHOT.jar
```

The application starts on `http://localhost:8080`.

### Start with Docker

```bash
docker-compose up --build
```

### Run Tests

```bash
# All tests (unit + integration + concurrency)
./gradlew clean test

# View test report
open build/reports/tests/test/index.html
```

## Verifying the Application

### Health Check

After starting, verify the application is up:

```bash
curl http://localhost:8080/api/v1/events
```

Should return `200 OK` with a JSON array (contains 2 seed events on first run).

### Swagger UI

Browse the interactive API docs at:
```
http://localhost:8080/swagger-ui.html
```

### H2 Database Console

Access the in-memory database directly at:
```
http://localhost:8080/h2-console
```

Connection settings:
- JDBC URL: `jdbc:h2:mem:ticketdb`
- Username: `sa`
- Password: (leave empty)

Useful queries:
```sql
-- Check all events
SELECT * FROM events;

-- Check seat status distribution for an event
SELECT status, COUNT(*) FROM seats WHERE event_id = 1 GROUP BY status;

-- Check active holds
SELECT * FROM seat_holds WHERE status = 'ACTIVE';

-- Check all bookings
SELECT * FROM bookings;

-- Verify no overbooking (held + booked should never exceed total_seats)
SELECT e.id, e.total_seats,
  (SELECT COUNT(*) FROM seats s WHERE s.event_id = e.id AND s.status IN ('HELD', 'BOOKED')) as occupied
FROM events e;
```

## Smoke Test Walkthrough

Complete end-to-end test using curl:

### 1. List Events (seed data)
```bash
curl -s http://localhost:8080/api/v1/events | jq
```

### 2. Check Availability
```bash
curl -s http://localhost:8080/api/v1/events/1/availability | jq
```

### 3. Hold Specific Seats
```bash
curl -s -X POST http://localhost:8080/api/v1/events/1/holds \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-1", "seatNumbers": ["1", "2", "3"]}' | jq
```

Save the `holdId` from the response.

### 4. Confirm Booking
```bash
curl -s -X POST http://localhost:8080/api/v1/bookings \
  -H "Content-Type: application/json" \
  -d '{"holdId": "<holdId-from-step-3>"}' | jq
```

Save the `bookingReference` from the response.

### 5. Verify Availability Changed
```bash
curl -s http://localhost:8080/api/v1/events/1/availability | jq '.availableCount, .bookedCount'
```

Should show `availableCount: 17, bookedCount: 3`.

### 6. Cancel Booking
```bash
curl -s -X PATCH http://localhost:8080/api/v1/bookings/<bookingReference>/cancel | jq
```

### 7. Verify Seats Released
```bash
curl -s http://localhost:8080/api/v1/events/1/availability | jq '.availableCount'
```

Should show `availableCount: 20`.

## Testing Concurrency

### Test with Concurrent Hold Requests

Use a tool like `xargs` or `parallel` to simulate concurrent requests:

```bash
# 10 concurrent users trying to hold seat "1"
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8080/api/v1/events/1/holds \
    -H "Content-Type: application/json" \
    -d "{\"userId\": \"user-$i\", \"seatNumbers\": [\"1\"]}" &
done
wait

# Check - only one should have succeeded
curl -s http://localhost:8080/api/v1/events/1/availability | jq '.heldCount'
```

Expected: `heldCount: 1` (exactly one user wins the seat).

## Configuration

### Application Properties

Key settings in `src/main/resources/application.yml`:

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | 8080 | HTTP port |
| `spring.datasource.url` | `jdbc:h2:mem:ticketdb;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000` | H2 connection URL |
| `spring.jpa.hibernate.ddl-auto` | validate | Schema validation mode |
| `spring.h2.console.enabled` | true | Enable H2 web console |

### Lock Timeout

The `LOCK_TIMEOUT=10000` in the JDBC URL means a thread waiting for a pessimistic lock will timeout after 10 seconds. This prevents indefinite waits if something goes wrong.

### Hold Duration

The hold expiry duration is set to 5 minutes in `HoldService.HOLD_DURATION_MINUTES`. The cleanup service runs every 60 seconds (`@Scheduled(fixedRate = 60000)` in `HoldCleanupService`).

## Troubleshooting

### Application won't start

**Symptom**: `Port 8080 already in use`
```bash
# Find what's using the port
lsof -i :8080
# Kill it or use a different port
SERVER_PORT=8081 ./gradlew bootRun
```

**Symptom**: `Schema validation failed` / table not found
- Ensure `schema.sql` is present in `src/main/resources/`
- Check `spring.sql.init.mode=always` in application.yml

### Tests fail with constraint violations

**Symptom**: `DataIntegrityViolationException` in `@BeforeEach`
- Cause: Foreign key constraint order during cleanup
- Fix: Delete in order: seats → bookings → seat_holds → events

### Hold expiry not working

- The cleanup scheduler runs every 60 seconds. A hold that expired 1 second ago may take up to 59 more seconds to be cleaned up.
- Verify the scheduler is enabled: check `SchedulerConfig` has `@EnableScheduling`
- Check logs for `Starting expired hold cleanup` messages

### Optimistic lock exception on event update

**Symptom**: `409 Conflict: The resource was modified by another request`
- Cause: Two concurrent PUT requests to update the same event
- Resolution: Client should retry the request (this is expected behavior)

## Logs

The application logs key events:

| Log Message | Meaning |
|------------|---------|
| `Starting expired hold cleanup` | Cleanup scheduler triggered |
| `Expired hold cleanup completed: released N holds` | N holds were expired and their seats released |

To increase log verbosity:
```bash
./gradlew bootRun --args='--logging.level.com.ticketbooking=DEBUG'
```

## Database Reset

Since H2 is in-memory, restarting the application resets all data. The seed events from `data.sql` are re-loaded on each startup.
