# Architecture & Design

## System Overview

The Ticket Booking System is a Spring Boot REST API implementing a **hold-then-confirm** reservation pattern. Users first place a temporary hold on specific seats, then confirm the hold to create a permanent booking. This two-phase approach prevents users from losing seats while entering payment details and allows the system to recover seats from abandoned sessions.

```
┌──────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────┐
│  Client  │────>│  Controller  │────>│   Service    │────>│   JPA    │
│          │<────│   (REST)     │<────│   (Logic)    │<────│  Repos   │
└──────────┘     └──────────────┘     └──────────────┘     └────┬─────┘
                                                                │
                                             ┌──────────────────┘
                                             │
                                       ┌─────▼─────┐
                                       │  H2 (DB)  │
                                       └───────────┘
```

## Layered Architecture

```
Controller Layer     →  Handles HTTP, validation, status codes
    ↓
Service Layer        →  Business logic, transactions, locking
    ↓
Repository Layer     →  JPA queries, data access
    ↓
Entity Layer         →  Domain model, DB mapping
```

Each layer has a single responsibility:

- **Controllers** only handle request/response mapping and delegate to services
- **Services** own all business logic and transaction boundaries
- **Repositories** provide data access through Spring Data JPA
- **Entities** map to database tables with JPA annotations

## Database Schema

```
┌────────────────┐       ┌────────────────┐
│     events     │       │   seat_holds   │
├────────────────┤       ├────────────────┤
│ id (PK)        │◄──┐   │ id (PK)        │
│ name           │   │   │ hold_id (UUID) │
│ description    │   ├───│ event_id (FK)  │
│ location       │   │   │ user_id        │
│ event_date     │   │   │ status         │
│ total_seats    │   │   │ expires_at     │
│ version        │   │   │ created_at     │
│ created_at     │   │   └───────┬────────┘
│ updated_at     │   │           │
└────────────────┘   │           │
        ▲            │           │
        │            │   ┌───────▼────────┐
        │            │   │    bookings    │
        │            │   ├────────────────┤
        │            │   │ id (PK)        │
        │            ├───│ event_id (FK)  │
        │            │   │ booking_ref    │
        │            │   │ user_id        │
        │            │   │ status         │
        │            │   │ hold_id (FK)───┘
        │            │   │ created_at     │
        │            │   │ canceled_at    │
        │            │   └───────┬────────┘
        │            │           │
┌───────┴────────┐   │           │
│     seats      │   │           │
├────────────────┤   │           │
│ id (PK, SEQ)   │   │           │
│ event_id (FK)──┼───┘           │
│ seat_number    │               │
│ status         │               │
│ hold_id (FK)───┼── → seat_holds│
│ booking_id(FK)─┼── → bookings │
│ updated_at     │
└────────────────┘
```

### Design Decisions

**Individual seats, not counts**: Each seat is a row in the `seats` table rather than a counter on the event. This allows users to hold *specific* seats ("1", "2", "3") and provides per-seat status tracking. The trade-off is more rows, but availability queries are simple status filters.

**hold_id and booking_id on seats, not a junction table**: A seat can belong to at most one hold and one booking at a time. This is 1:N, not M:N, so a junction table would add complexity without benefit.

**No `seats_requested`/`seats_booked` columns**: The count of held/booked seats is derived from the `seats` table. This avoids data duplication and the risk of counts getting out of sync with actual seat records.

**SEQUENCE generator for seats**: The `seats` table uses `GenerationType.SEQUENCE` with `allocationSize=50` instead of `IDENTITY`. This enables Hibernate batch inserts (50 at a time) when creating an event, since `IDENTITY` forces immediate per-row inserts to retrieve auto-generated IDs.

**Soft deletes for bookings**: Canceled bookings remain in the `bookings` table with `status=CANCELED` and `canceled_at` set. This preserves the audit trail.

## Concurrency Strategy

### The Problem

When multiple users try to book seats for the same event simultaneously, classic race conditions arise:

- Two users check seat "5" is available, both try to hold it → double-hold
- A user confirms a hold while the cleanup service tries to expire it → inconsistent state
- Many concurrent holds could collectively exceed `totalSeats`

### The Solution: Pessimistic Locking on Event Row

All seat-modifying operations acquire a **pessimistic write lock** (`SELECT ... FOR UPDATE`) on the event row before making any changes. This serializes operations **per event** while allowing operations on different events to proceed concurrently.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT e FROM Event e WHERE e.id = :eventId")
Optional<Event> findByIdWithLock(@Param("eventId") Long eventId);
```

### What gets serialized


| Operation            | Acquires Event Lock? | Why                                         |
| -------------------- | -------------------- | ------------------------------------------- |
| Hold seats           | Yes                  | Must check and update seat availability     |
| Confirm booking      | Yes                  | Must validate hold and transition seats     |
| Cancel booking       | Yes                  | Must release seats back to available        |
| Expired hold cleanup | Yes (per event)      | Must release seats without racing confirm   |
| Read availability    | No                   | Read-only, eventual consistency acceptable  |
| Event CRUD update    | No (uses @Version)   | Optimistic locking sufficient for admin ops |


### Why pessimistic, not optimistic?


| Concern                | Pessimistic                | Optimistic                      |
| ---------------------- | -------------------------- | ------------------------------- |
| Conflict handling      | Block until lock released  | Detect at commit, retry         |
| Under high contention  | Threads queue, all succeed | Cascade of retries, wasted work |
| Complexity             | Lock once, proceed         | Retry loops, backoff logic      |
| Event row modification | Not required               | Must artificially bump version  |


For seat operations, we are not modifying the event row itself - only seat and hold rows. Optimistic locking would require artificially bumping the event version on every hold, which is semantically wrong and creates a hot-spot. Pessimistic locking is the natural fit.

### Race Condition: Confirm vs Cleanup

The most subtle race condition is between a user confirming a hold and the cleanup service expiring it:

```
Timeline:
  T1: Cleanup scans, finds hold H1 expired (status=ACTIVE, expires_at in past)
  T2: User sends confirm for hold H1
  T3: One thread acquires the event lock, the other blocks
  T4: Winner proceeds, loser gets stale data
```

**Protection mechanisms:**

1. Both operations acquire the same event lock → they never run concurrently for the same event
2. Confirm re-fetches the hold *after* acquiring the lock → sees cleanup's changes
3. Cleanup re-checks hold status after acquiring the lock → sees confirm's changes
4. Cleanup uses `if (hold.getStatus() != ACTIVE) continue` as a guard

## Hold-Then-Confirm Workflow

```
User                    System                          Database
 │                        │                               │
 │  POST /holds           │                               │
 │  {seats: [1,2,3]}      │                               │
 │───────────────────────>│                               │
 │                        │  Lock event row               │
 │                        │──────────────────────────────>│
 │                        │  Check seats available        │
 │                        │  Create hold (5 min expiry)   │
 │                        │  Mark seats as HELD           │
 │                        │  Commit (release lock)        │
 │  201 {holdId: abc}     │                               │
 │<───────────────────────│                               │
 │                        │                               │
 │  ... user enters       │                               │
 │  ... payment info      │                               │
 │                        │                               │
 │  POST /bookings        │                               │
 │  {holdId: abc}         │                               │
 │───────────────────────>│                               │
 │                        │  Lock event row               │
 │                        │──────────────────────────────>│
 │                        │  Validate hold ACTIVE         │
 │                        │  Check not expired            │
 │                        │  Create booking               │
 │                        │  Seats: HELD → BOOKED         │
 │                        │  Hold: ACTIVE → CONFIRMED     │
 │                        │  Commit (release lock)        │
 │  201 {bookingRef: xyz} │                               │
 │<───────────────────────│                               │
```

If the user doesn't confirm within 5 minutes:

```
Scheduler (every 60s)     System                          Database
 │                        │                                │
 │  Trigger cleanup       │                                │
 │───────────────────────>│                                │
 │                        │  Find events with expired holds│
 │                        │  For each event:               │
 │                        │    Lock event row              │
 │                        │    Re-check hold still ACTIVE  │
 │                        │    Hold: ACTIVE → EXPIRED      │
 │                        │    Seats: HELD → AVAILABLE     │
 │                        │    Commit (release lock)       │
 │                        │                                │
```

## Business Rules


| Rule                                    | Enforced By                                     |
| --------------------------------------- | ----------------------------------------------- |
| Seats never overbooked                  | Pessimistic lock + individual seat status check |
| No duplicate active hold per user/event | Application check inside locked transaction     |
| No duplicate confirmed booking          | Application check inside locked transaction     |
| Hold expires after 5 minutes            | Scheduled cleanup + confirm-time expiry check   |
| Soft deletes for bookings               | Status field (CONFIRMED/CANCELED)               |
| totalSeats > 0                          | DB CHECK constraint + validation annotation     |
| Seat numbers valid for event            | Validated against `seats` table in hold flow    |


## Error Handling

All exceptions are mapped to appropriate HTTP status codes by `GlobalExceptionHandler`:


| Exception                       | HTTP Status | When                                    |
| ------------------------------- | ----------- | --------------------------------------- |
| ResourceNotFoundException       | 404         | Event/hold/booking not found            |
| SeatsUnavailableException       | 409         | Requested seats already held/booked     |
| InvalidSeatException            | 400         | Seat numbers don't exist for this event |
| DuplicateHoldException          | 409         | User already has active hold for event  |
| DuplicateBookingException       | 409         | User already has confirmed booking      |
| HoldExpiredException            | 410         | Hold past 5-minute window               |
| InvalidHoldStateException       | 409         | Hold already confirmed/expired          |
| BookingAlreadyCanceledException | 409         | Booking already canceled                |
| OptimisticLockingFailure        | 409         | Concurrent event CRUD update            |
| MethodArgumentNotValid          | 400         | Input validation failure                |


## Technology Choices


| Choice             | Reason                                                  |
| ------------------ | ------------------------------------------------------- |
| Spring Boot 3.4    | Industry standard, mature ecosystem                     |
| H2 in-memory       | Zero setup for evaluators, supports SELECT FOR UPDATE   |
| Gradle             | Modern build tool, faster incremental builds than Maven |
| Lombok             | Reduces boilerplate (getters, setters, builders)        |
| Jakarta Validation | Declarative input validation at controller boundary     |
| springdoc-openapi  | Auto-generated Swagger UI from code annotations         |
| JUnit 5 + Mockito  | Standard testing stack for Spring Boot                  |


