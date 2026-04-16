# Concurrency in Seat Holding — A First-Principles Guide

This document walks through *why* the seat-holding code looks the way it does.
It is written as a teaching guide: we start from the problem, develop the
solution space ourselves, compare alternatives honestly, and only then arrive
at the implementation. Every design decision is anchored to a concrete line of
code.

If you are a future maintainer wondering "why didn't they just use a lock?" or
"why is this UPDATE statement so weirdly shaped?" — this is the document for
you.

---

## 1. Defining correctness before defining solutions

Before we can evaluate approaches, we must state what "correct" means. A
booking system must enforce two hard invariants, on pain of catastrophe:

- **Invariant A — Seat exclusivity.** At any moment in time, a seat belongs
  to at most one hold or booking. If two users each leave the page believing
  they got seat 7, the business loses trust permanently.

- **Invariant B — User uniqueness.** A user has at most one *ACTIVE* hold per
  event. Without this, a user can hoard seats by issuing parallel requests,
  starving other users and bypassing any rate limit.

Everything else in the design — throughput, latency, error messages, code
simplicity — is a tradeoff layered on top of these two non-negotiables. If a
design breaks A or B under any concurrency pattern, it does not count as a
solution, no matter how fast it is.

Keep this lens in mind for the rest of the document: *does this approach
enforce A and B, and at what cost?*

---

## 2. The fundamental problem — the check-then-act race

The naive implementation of "hold a seat" looks like this, and it is broken:

```
read seat.status
if status == AVAILABLE:
    seat.status = HELD
    seat.hold_id = my_hold
```

Two threads executing concurrently produce this interleaving:

```
T1: reads status = AVAILABLE
T2: reads status = AVAILABLE     ← both see the same thing
T1: writes status = HELD         ← T1 "wins"
T2: writes status = HELD         ← T2 silently overwrites
```

Both threads think they succeeded. Both return a hold confirmation to their
respective users. The database has one seat marked HELD. At the box office,
two people show up with tickets.

This is called a **TOCTOU race** — Time-Of-Check to Time-Of-Use. The gap
between reading a value and acting on it is where the race lives. The width
of that gap — in nanoseconds — does not matter; any gap at all is enough,
because concurrency is adversarial.

**The entire concurrency-control design space exists to solve this one
problem: how do we make the check and the act indivisible?** Every approach
we will consider is ultimately a different answer to that question.

---

## 3. Who provides atomicity?

"Indivisible" has to come from some authority. Something must be able to say
"no other operation interleaves between these two steps." The candidates, from
heaviest to lightest:

| Authority                      | Scope                | Cost per operation      |
|--------------------------------|----------------------|-------------------------|
| Application-level mutex        | One JVM              | Nanoseconds (but doesn't work across instances) |
| Distributed lock (Redis, ZK)   | Whole cluster        | Network round-trip (~1ms+) |
| DB explicit lock (`FOR UPDATE`)| One row (for whole txn) | Milliseconds, held long |
| DB row write (UPDATE)          | One row (for statement) | Microseconds, held briefly |
| DB uniqueness constraint       | One index entry      | Microseconds, no lock held |

Two observations drive our thinking:

1. The database is already in the critical path. Every hold request already
   writes to it. Using the DB as the atomicity primitive costs us nothing
   extra.

2. The DB's primitives vary by two orders of magnitude in how long locks are
   held. An explicit `SELECT ... FOR UPDATE` lock is held for the entire
   transaction — tens of milliseconds. A plain UPDATE's row lock is held only
   for the duration of that statement — microseconds.

The design axis we will explore is: *which DB primitive do we lean on, and
for how long does it hold its lock?* Every approach we consider is a specific
point on this axis.

---

## 4. The four approaches, developed from first principles

### Approach 0 — Pessimistic lock on the event row

**The idea.** Before doing anything for an event, grab a write lock on the
event row itself (`SELECT ... FOR UPDATE`). The event row becomes a
coarse-grained mutex. Only one transaction at a time can be "inside" hold /
confirm / release for a given event.

**Why it works.** Nothing else can happen on that event while you hold the
lock, so TOCTOU becomes impossible by construction. The check and the act
both happen inside your critical section.

**How it looks in code.** The original implementation was literally this:

```java
// what the code USED to be
Event event = eventRepository.findByIdWithLock(eventId)   // SELECT ... FOR UPDATE
        .orElseThrow(...);

if (seatHoldRepository.existsByEventIdAndUserIdAndStatus(
        eventId, userId, HoldStatus.ACTIVE)) {
    throw new DuplicateHoldException(...);                // Invariant B
}

List<Seat> seats = seatRepository.findByEventIdAndSeatNumberIn(eventId, seatNumbers);
// ... validate status=AVAILABLE ...                      // Invariant A check
seats.forEach(s -> s.setStatus(HELD));
seatRepository.saveAll(seats);                            // Invariant A act
```

**Pros.**

- *Trivially correct.* The event lock is a sledgehammer — nothing subtle.
- *Simple mental model.* One lock, one rule: "everything for this event is
  serialized."
- *Handles both invariants uniformly.* The duplicate-hold check at line 2 and
  the seat-availability check at line 8 are both protected by the same lock
  acquired at line 1.
- *No deadlocks possible.* You only ever lock one row.

**Cons.**

- *Zero concurrency inside an event.* Two users trying to hold completely
  disjoint seat sets ({1,2,3} and {50,51,52}) still serialize. The lock has
  no knowledge of what seats are being touched — it's "the whole event."
- *The lock is held for the entire transaction*, typically 20-50ms including
  all reads, writes, and JPA overhead. For a popular event during a traffic
  spike, this becomes the bottleneck.
- *Throughput ceiling per event: ~20-50 requests per second,* independent of
  how powerful your database is. You've serialized the workload in
  application code.

**When it's the right choice.** Low-traffic events, small venues, or systems
where simplicity dominates performance. Useful as a starting point, rarely
the right long-term answer for ticketing.

---

### Approach 1 — Pessimistic lock on individual seats

**The idea.** The event lock is too coarse — it protects seat 1 even when
you only care about seat 7. So shrink the lock: lock only the specific rows
you're about to modify.

**Why it works.** Two users requesting disjoint seat sets never contend on
any row lock, so they proceed in full parallel. Users contending on the same
seat still serialize — correctly — one row at a time.

**How it would look.**

```java
// add to SeatRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Seat s WHERE s.event.id = :eventId " +
       "AND s.seatNumber IN :seatNumbers ORDER BY s.id")
List<Seat> findByEventIdAndSeatNumberInWithLock(
        @Param("eventId") Long eventId,
        @Param("seatNumbers") List<String> seatNumbers);
```

Notice `ORDER BY s.id`. This is not cosmetic — it prevents deadlock. If user
A locks seats in a different order than user B, and their sets overlap, they
can wait on each other forever. Fixed ordering across *every* caller
eliminates the cycle. This discipline must be maintained everywhere seats
are locked (booking, cancellation, expiration cleanup, admin tools).

**Pros.**

- *Disjoint-seat concurrency works.* The whole motivation.
- *Correctness is still guaranteed by the DB* — pessimistic locks never fail.
- *Familiar model.* Anyone who understands `SELECT ... FOR UPDATE` can
  understand this.

**Cons.**

- *Deadlock risk is now real* and requires team-wide discipline. A new
  service method that locks seats in a different order silently reintroduces
  the bug.
- *Invariant B is no longer protected by the lock.* The lock only covers
  seats, not the duplicate-hold check. You must add a unique constraint on
  `seat_holds` to enforce it — suddenly two mechanisms instead of one.
- *Locks are still held for the full transaction.* Improved granularity, but
  same duration. Under heavy contention on a popular seat, threads still
  park.
- *Blocking semantics.* When `SELECT ... FOR UPDATE` conflicts, the loser
  *waits* for the winner's transaction to complete, then acquires the lock,
  re-reads the row, and discovers it's now HELD — then fails in application
  logic. The latency cost is A's transaction time on top of B's own work.

**When it's the right choice.** Medium-scale systems where you want
per-event parallelism and can afford the team cost of deadlock discipline.
Rarely optimal — usually one of the alternatives below is better.

---

### Approach 2 — Optimistic locking on seats

**The idea.** Don't lock at all. Read rows freely. When writing, *detect*
whether anyone modified the row since we read it. If they did, abort and
retry. This is the same pattern as HTTP ETags, DynamoDB conditional writes,
and hardware CAS instructions — "hope for the best, verify at write time."

**How it works mechanically.** Add a `@Version` column to the entity:

```java
@Entity
public class Seat {
    @Id private Long id;
    private SeatStatus status;

    @Version
    private Long version;   // 0 on insert, Hibernate increments on every update
}
```

Hibernate captures the version at read time. At write time, it generates:

```sql
UPDATE seats
SET status = ?, version = version + 1
WHERE id = ?
  AND version = ?    -- ← the value captured at read
```

The check (`version = ?`) and the act (set new status, bump version) are a
single atomic SQL statement. The DB returns the affected-row count:

- **1 row affected** → nobody changed the row between our read and our write.
  Our UPDATE wins, `version` is now incremented, commit proceeds.
- **0 rows affected** → someone else changed the row (their UPDATE bumped
  `version`, so ours no longer matches). Hibernate throws
  `ObjectOptimisticLockingFailureException`. The transaction is marked
  rollback-only.

**Concrete timeline.** Two threads holding seat 5, starting state
`(status='AVAILABLE', version=0)`:

```
T1: SELECT seat 5 → reads (AVAILABLE, v=0). Hibernate remembers v=0.
T2: SELECT seat 5 → reads (AVAILABLE, v=0). Hibernate remembers v=0.  (no lock!)
T1: seat.setStatus(HELD); commit.
    UPDATE seats SET status='HELD', version=1 WHERE id=5 AND version=0
    → 1 row affected ✓.  DB now (HELD, v=1).
T2: seat.setStatus(HELD); commit.
    UPDATE seats SET status='HELD', version=1 WHERE id=5 AND version=0
    → 0 rows affected ✗.  Hibernate throws, ROLLBACK.
```

Both threads read without waiting. The conflict is detected only at write
time, by the row-count check. Nothing is silently overwritten — T2 is told
definitively that its view was stale.

**Retry mechanics.** Once the exception is thrown, the transaction is dead
— you can't catch-and-continue in the same transaction. The retry must wrap
the transactional method *from the outside*:

```java
@Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
           maxAttempts = 3, backoff = @Backoff(delay = 20))
public HoldResponse holdSeats(...) {
    return self.holdSeatsTransactional(...);   // new transaction per retry
}

@Transactional
public HoldResponse holdSeatsTransactional(...) { /* read, mutate, save */ }
```

Each retry opens a fresh transaction and re-reads everything. Requires
`@EnableRetry`, self-injection (so the Spring proxy fires), and usually
`@Recover` for when retries are exhausted.

**Pros.**

- *Lock-free on the happy path.* No DB lock-table pressure, no waiting.
- *Excellent throughput when conflicts are rare.*
- *No deadlocks possible.* There are no locks to cycle.
- *Natural fit for long-lived user sessions* — e.g., a document editor
  where a user reads, edits for 10 minutes, then saves. You can't hold a
  pessimistic lock that long.

**Cons.**

- *Retries don't help when conflicts are permanent.* In booking, if B lost
  the race for seat 5, retry re-reads it as HELD and just fails with
  `SeatsUnavailableException`. The retry is wasted latency and DB load.
  Retries only help for *incidental* version bumps (e.g., the expiration
  job releasing an unrelated hold on the same seat).
- *Retry scaffolding is subtle.* Self-injection, `@EnableRetry`,
  transactional boundary discipline, `@Recover` — every one is a real bug
  pattern people get wrong.
- *Row-scoped false conflicts.* The version bumps on any column change, so
  two transactions modifying different fields of the same row collide even
  though they don't logically conflict.
- *Poisoned transactions.* After a conflict, the transaction is
  rollback-only — you must unwind fully before retrying.

**When it's the right choice.** Long transactions where locks can't be
held (editors, workflows), user-driven merge UIs (present the conflict and
let the user decide), distributed systems where lock coordination across
shards is expensive. *Not* the right fit for short, same-row-contested
transactional workloads like booking — which is why we chose Approach 3
instead.

---

### Approach 3 — Conditional UPDATE (the chosen design)

**The idea.** Don't lock, don't retry. Express the check and the act as a
*single SQL statement* whose `WHERE` clause includes the pre-condition:

```sql
UPDATE seats
SET status = 'HELD', hold_id = ?
WHERE event_id = ?
  AND seat_number IN (...)
  AND status = 'AVAILABLE'         -- ← this is the "check"
```

The DB is *already* atomic per row-write. It locks the row, re-evaluates the
`WHERE` clause under the lock, writes if the row still matches, and releases
the lock. The check-then-act race is impossible because the check and act are
the same statement.

**Why this is qualitatively different from Approach 2.** Optimistic locking
and conditional UPDATE both rely on the DB to detect conflicts. The
difference is *what information the DB returns*:

- Optimistic lock: throws an exception on any version mismatch. Tells you
  "something changed," not what.
- Conditional UPDATE: returns the *affected row count*. Tells you exactly how
  many rows your predicate matched.

That row count is the oracle. `claimed == requested` means success —
unambiguously. `claimed < requested` means some seats weren't AVAILABLE —
unambiguously. There is no "maybe it worked, better retry" state. The failure
model is deterministic.

**How it looks in code.**

The SQL is a JPQL `@Modifying` query in
[SeatRepository.java:32-38](../src/main/java/com/ticketbooking/repository/SeatRepository.java#L32-L38):

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE Seat s SET s.status = com.ticketbooking.entity.enums.SeatStatus.HELD, s.hold = :hold " +
       "WHERE s.event.id = :eventId AND s.seatNumber IN :seatNumbers " +
       "AND s.status = com.ticketbooking.entity.enums.SeatStatus.AVAILABLE")
int claimSeats(@Param("eventId") Long eventId,
               @Param("seatNumbers") List<String> seatNumbers,
               @Param("hold") SeatHold hold);
```

The service call is one line plus a row-count check
([HoldService.java:75-79](../src/main/java/com/ticketbooking/service/HoldService.java#L75-L79)):

```java
int claimed = seatRepository.claimSeats(eventId, seatNumbers, hold);
if (claimed != seatNumbers.size()) {
    throwClaimFailure(eventId, seatNumbers, hold);
}
```

**Invariant B gets the same treatment.** Instead of an application-level
duplicate-hold check (which has a TOCTOU race without an event lock), we
push the invariant into a unique constraint and let the DB enforce it
atomically at INSERT time. Three pieces work together:

A column that equals `userId` while the hold is ACTIVE, NULL otherwise
([SeatHold.java:45-46](../src/main/java/com/ticketbooking/entity/SeatHold.java#L45-L46)):

```java
@Column(name = "active_hold_key")
private String activeHoldKey;
```

Lifecycle hooks that keep the column in sync with status
([SeatHold.java:54-68](../src/main/java/com/ticketbooking/entity/SeatHold.java#L54-L68)):

```java
@PrePersist
protected void onCreate() {
    createdAt = LocalDateTime.now();
    syncActiveHoldKey();
}

@PreUpdate
protected void onUpdate() {
    syncActiveHoldKey();
}

private void syncActiveHoldKey() {
    this.activeHoldKey = (status == HoldStatus.ACTIVE) ? userId : null;
}
```

A unique constraint on the pair
([SeatHold.java:12-15](../src/main/java/com/ticketbooking/entity/SeatHold.java#L12-L15)):

```java
@Table(name = "seat_holds", uniqueConstraints = {
        @UniqueConstraint(name = "uq_active_hold_per_user_event",
                          columnNames = {"event_id", "active_hold_key"})
})
```

The trick is SQL's treatment of NULL in unique constraints: NULLs are
considered *distinct* from each other. So a user can have many historical
holds (all with `active_hold_key = NULL`) but at most one ACTIVE hold (with
`active_hold_key = userId`). This is the portable equivalent of a
partial unique index (which Postgres supports natively but H2 and MySQL do
not).

Concurrent INSERTs from the same user hit the unique index's B-tree at the
same entry. The DB serializes the index write at microsecond granularity,
and exactly one INSERT wins. The other raises a constraint violation, which
Spring translates to `DataIntegrityViolationException`. We catch it at
[HoldService.java:68-70](../src/main/java/com/ticketbooking/service/HoldService.java#L68-L70)
and throw `DuplicateHoldException`.

**Pros.**

- *Both invariants enforced atomically by the DB.* Invariant A via
  conditional UPDATE; Invariant B via unique constraint. The service code
  cannot violate them, even accidentally.
- *Maximum concurrency.* No locks held across statements. The only
  serialization is the microsecond-scale row-write lock *during* an UPDATE,
  and the index-entry lock *during* an INSERT. Disjoint work runs in full
  parallel.
- *Deterministic failure model.* The UPDATE's row count tells you exactly
  what happened. No retry ambiguity.
- *No retry logic needed.* The happy path and the failure path are both
  decided in one SQL round-trip.
- *No deadlock risk.* Application code doesn't acquire locks across multiple
  rows — the DB does that internally, consistently.
- *Code is short and uncluttered.* No `@Retryable`, no `@Recover`, no split
  transactional methods, no lock-ordering comments.

**Cons.**

- *Error diagnosis requires a follow-up SELECT.* When `claimed < requested`,
  we need to tell the user *which* seats failed and why (missing vs.
  unavailable). This is an extra round-trip on the unhappy path — rare and
  cheap. See [HoldService.throwClaimFailure](../src/main/java/com/ticketbooking/service/HoldService.java#L87-L102).
- *JPA's session cache gets stale.* The bulk UPDATE bypasses Hibernate's
  entity lifecycle. We handle this with `clearAutomatically = true` on the
  `@Modifying` annotation so stale entities are evicted.
- *Operates at SQL level, not entity level.* Less natural fit with JPA's
  entity-graph abstractions. For a concurrent-critical path this is
  absolutely worth it.

**When it's the right choice.** Almost always for booking-style workloads.
It is the thinnest possible layer over guarantees the DB gives us for free.

---

## 5. Summary table

| Aspect                        | 0: Event lock | 1: Seat pess. lock | 2: Optimistic   | **3: Conditional UPDATE** |
|-------------------------------|---------------|--------------------|-----------------|---------------------------|
| Enforces Invariant A          | ✓ (coarse)    | ✓                  | ✓ (after retry) | ✓ (per-row, atomic)       |
| Enforces Invariant B          | ✓ (via lock)  | Needs unique index | Needs unique index | Needs unique index     |
| Concurrency on disjoint seats | None          | High               | High            | **Highest**               |
| Concurrency on same seat      | None          | Serialized, long   | Degrades under load | Serialized, microseconds |
| Retry logic required          | No            | No                 | **Yes**         | No                        |
| Deadlock risk                 | None          | Yes (order-sensitive) | None         | None                      |
| Failure-path clarity          | Simple        | Simple             | Ambiguous       | **Deterministic**         |
| Lock footprint                | 1 event row, whole txn | N seats, whole txn | None | N seats, microseconds     |
| Code complexity               | Low           | Medium             | High            | Low                       |
| Portability across DBs        | Universal     | Universal          | Universal       | Universal                 |

---

## 6. Full code walkthrough of the chosen design

This section traces every line of
[HoldService.holdSeats](../src/main/java/com/ticketbooking/service/HoldService.java#L45-L85)
and ties it back to the concepts above.

### 6.1 The transaction envelope

[HoldService.java:45](../src/main/java/com/ticketbooking/service/HoldService.java#L45)
```java
@Transactional
public HoldResponse holdSeats(Long eventId, HoldSeatsRequest request) {
```

`@Transactional` wraps the whole method body in one DB transaction. This is
the primitive that makes the hold INSERT and the seat UPDATE an
all-or-nothing unit. Without it, a partial failure — say, the INSERT
succeeds but the UPDATE claims fewer seats than requested — would leave the
DB with an orphan hold row.

Spring's default rollback rule: any `RuntimeException` escaping the method
triggers a ROLLBACK. Our exception types
(`InvalidSeatException`, `SeatsUnavailableException`,
`DuplicateHoldException`) all extend `RuntimeException`, so rollback is
automatic with no `rollbackFor` configuration needed.

### 6.2 Input validation (client-side concerns)

[HoldService.java:47-50](../src/main/java/com/ticketbooking/service/HoldService.java#L47-L50)
```java
List<String> seatNumbers = request.getSeatNumbers();
if (seatNumbers.size() != new HashSet<>(seatNumbers).size()) {
    throw new IllegalArgumentException("Duplicate seat numbers in request");
}
```

This is not concurrency-related. We reject malformed requests before
touching the DB. If the client sends `["1", "1"]`, the conditional UPDATE
would also catch it (affected rows = 1, not 2), but it's cleaner to fail
upfront with a 400-level error rather than a 409.

### 6.3 Loading the event (no lock!)

[HoldService.java:52-53](../src/main/java/com/ticketbooking/service/HoldService.java#L52-L53)
```java
Event event = eventRepository.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
```

**This is the single biggest change from Approach 0.** Previously, this line
was `findByIdWithLock(eventId)` — a `SELECT ... FOR UPDATE` that serialized
every hold request behind a single row lock.

Now it's a plain read. The event is loaded only because the `SeatHold` row
we're about to insert needs it as a foreign-key reference. Nothing else.
Two requests for the same event can proceed in parallel from here on.

### 6.4 Inserting the hold with Invariant B enforcement

[HoldService.java:58-70](../src/main/java/com/ticketbooking/service/HoldService.java#L58-L70)
```java
SeatHold hold = SeatHold.builder()
        .holdId(UUID.randomUUID())
        .event(event)
        .userId(request.getUserId())
        .status(HoldStatus.ACTIVE)
        .expiresAt(LocalDateTime.now(clock).plusMinutes(holdDurationMinutes))
        .build();
try {
    hold = seatHoldRepository.saveAndFlush(hold);
} catch (DataIntegrityViolationException e) {
    throw new DuplicateHoldException(eventId, request.getUserId());
}
```

Two things are happening here. Let's pull them apart.

**First — the `@PrePersist` fires before the INSERT**, at
[SeatHold.java:54-58](../src/main/java/com/ticketbooking/entity/SeatHold.java#L54-L58),
and sets `activeHoldKey = userId` because `status == ACTIVE`. This is the
column that participates in the unique constraint.

**Second — `saveAndFlush` rather than `save`.** `save` only stages the
INSERT in the Hibernate session; the SQL doesn't actually run until the
transaction commits or until Hibernate auto-flushes before another query.
We need the INSERT to run *now*, so that if it's going to fail a unique
constraint, it fails *here*, where we can catch it as a localized exception
and translate it to `DuplicateHoldException`.

**How the race gets solved.** Two threads for the same user generate rows
with the same `(event_id, active_hold_key)`. The unique index is a B-tree,
and inserting into a B-tree is an atomic operation at the DB level — the
index acquires a lock on the leaf page, checks for the key, and either
writes or rejects. Exactly one INSERT wins. The other raises a constraint
violation, JDBC propagates it, and Spring translates it to
`DataIntegrityViolationException`. We catch and rethrow as
`DuplicateHoldException`.

No application-level check, no `existsBy...` query, no TOCTOU gap. The DB's
own data structure enforces Invariant B.

### 6.5 Claiming the seats with Invariant A enforcement

[HoldService.java:75-79](../src/main/java/com/ticketbooking/service/HoldService.java#L75-L79)
```java
int claimed = seatRepository.claimSeats(eventId, seatNumbers, hold);
if (claimed != seatNumbers.size()) {
    throwClaimFailure(eventId, seatNumbers, hold);
}
```

The SQL underneath, from
[SeatRepository.java:32-38](../src/main/java/com/ticketbooking/repository/SeatRepository.java#L32-L38):

```sql
UPDATE seats
SET status = 'HELD', hold_id = ?
WHERE event_id = ?
  AND seat_number IN (...)
  AND status = 'AVAILABLE'
```

This is the heart of the design. For every row that matches the full
predicate, the DB:

1. Takes a row-level write lock on that row.
2. Re-reads the row under the lock to re-evaluate the predicate
   (specifically `status = 'AVAILABLE'`).
3. If the predicate still holds, writes the new values.
4. Releases the row lock.

The critical observation: the check (`status = 'AVAILABLE'`) and the write
(`SET status = 'HELD'`) happen under the same lock, on the same row, within
one statement. No other transaction can observe or modify the row between
the check and the write. The TOCTOU race is closed at the row level.

**How concurrent requests interact.**

- *Disjoint seat sets ({1,2} and {50,51}).* No row locks overlap. Both
  UPDATEs complete in full parallel.
- *Overlapping seat sets ({1,2,3} and {3,4,5}).* Both UPDATEs race for the
  row lock on seat 3. The DB grants it to whichever gets there first
  (microseconds). The winner claims the row; when the loser's UPDATE tries
  to lock the row, by the time the lock is granted the row's status is
  already HELD, so the predicate fails and the row is skipped. The loser's
  `claimed` count comes back as 2 (not 3), and we throw
  `SeatsUnavailableException`.

At no point does any transaction wait for *another transaction's duration*.
The only waiting is at the row-write level, which is microseconds.

### 6.6 Why the row count is the oracle

A conditional UPDATE's return value is the number of rows that matched *and
were updated*. This number tells us the outcome unambiguously:

- `claimed == seatNumbers.size()` → every requested seat was AVAILABLE at
  the moment of UPDATE and is now HELD by our hold. Invariant A is
  preserved (we are the only holder), and success is guaranteed.
- `claimed < seatNumbers.size()` → at least one seat didn't match. Either
  because:
  - The seat number doesn't exist in the DB at all, or
  - Another transaction claimed it first (status was HELD/BOOKED).

We cannot tell *which* from the row count alone — so we do one diagnostic
SELECT in the error path (see 6.8).

### 6.7 Rollback on partial failure

[HoldService.java:76-79](../src/main/java/com/ticketbooking/service/HoldService.java#L76-L79)
```java
if (claimed != seatNumbers.size()) {
    throwClaimFailure(eventId, seatNumbers, hold);
}
```

If some seats failed, we throw. Spring sees the `RuntimeException` exit the
`@Transactional` method and issues a ROLLBACK. The hold row we inserted at
step 6.4 — along with any partial seat updates from the conditional
UPDATE — is undone in a single DB operation. The database is restored to
exactly the state it was in before the request.

This is why there is no inconsistency risk even on partial success of the
UPDATE. The transaction boundary turns "partially claimed" into "atomically
aborted."

### 6.8 Diagnosing which seats failed (off the happy path)

[HoldService.java:87-102](../src/main/java/com/ticketbooking/service/HoldService.java#L87-L102)
```java
private void throwClaimFailure(Long eventId, List<String> seatNumbers, SeatHold hold) {
    List<Seat> existing = seatRepository.findByEventIdAndSeatNumberIn(eventId, seatNumbers);
    Set<String> foundNumbers = existing.stream().map(Seat::getSeatNumber).collect(Collectors.toSet());
    List<String> missing = seatNumbers.stream()
            .filter(num -> !foundNumbers.contains(num))
            .toList();
    if (!missing.isEmpty()) {
        throw new InvalidSeatException(missing);
    }
    List<String> unavailable = existing.stream()
            .filter(s -> s.getHold() == null || !hold.getId().equals(s.getHold().getId()))
            .map(Seat::getSeatNumber)
            .toList();
    throw new SeatsUnavailableException(unavailable);
}
```

This is the small price we pay for not retrying. When the UPDATE tells us
"some seats were not claimable," we do one SELECT to distinguish:

- *Missing seats* — don't exist in the DB → `InvalidSeatException` (404-like).
- *Unavailable seats* — exist but are HELD/BOOKED by someone else →
  `SeatsUnavailableException` (409-like).

The filter `s.getHold() == null || !hold.getId().equals(s.getHold().getId())`
excludes seats we *did* claim in the partial UPDATE — those are tied to our
hold via `hold_id`. The unclaimed ones are the blockers we want to report.

Because we throw from this method, the enclosing transaction rolls back and
even our own partially-claimed seats revert. The diagnostic SELECT is
performed only to produce a helpful error message.

### 6.9 Happy-path commit

[HoldService.java:81-84](../src/main/java/com/ticketbooking/service/HoldService.java#L81-L84)
```java
log.info("Hold created: holdId={}, userId={}, eventId={}, seats={}, expiresAt={}",
        hold.getHoldId(), request.getUserId(), eventId, seatNumbers, hold.getExpiresAt());

return toHoldResponse(hold, seatNumbers);
```

Both invariants proven, both writes staged in the DB. The `@Transactional`
COMMIT happens implicitly when the method returns normally. At that
instant, the hold row and all the seat updates become visible to every
other transaction simultaneously.

---

## 7. The guiding principle

Everything above reduces to one idea:

> **Push invariants down to the database, in the form of atomic statements
> and constraints. Keep the application code's job to the minimum: describe
> intent, observe outcome, translate errors into domain exceptions.**

The DB is a highly optimized, battle-tested concurrency engine. It already
does compare-and-set atomically (row write), already enforces uniqueness
atomically (unique index), already handles rollback atomically (transaction
boundary). Every concurrency primitive we build in application code —
pessimistic locks, optimistic retries, distributed coordination — is
*reinventing* what the DB gives us for free, but coarser and slower.

The thinner the application code can be, the stronger the correctness
guarantees and the higher the throughput. Approach 3 is the thinnest layer
we can write over what the DB already guarantees: one INSERT (enforcing
Invariant B via unique constraint), one UPDATE (enforcing Invariant A via
conditional predicate), and an envelope that makes them atomic.

That's why this design wins. Not because it's clever — because it isn't
doing the DB's job.

---

## 8. What to watch out for when extending this design

- **Don't reintroduce application-level pre-checks "for convenience."** An
  `existsByEventIdAndUserIdAndStatus` call before the INSERT adds a TOCTOU
  race and provides no benefit that the unique constraint doesn't already
  give you.
- **Don't add `@Retryable` to the service method.** The failure model is
  already deterministic. Retrying just delays the response and burns
  resources on guaranteed failures.
- **Keep `active_hold_key` in sync with `status` via the entity lifecycle,
  not manually in callers.** If someone changes a hold's status without
  going through the entity's setter + save flow, the column drifts and the
  unique constraint silently under-enforces. The `@PreUpdate` hook at
  [SeatHold.java:60-63](../src/main/java/com/ticketbooking/entity/SeatHold.java#L60-L63)
  is what keeps the invariant honest.
- **Other services that mutate seats** (BookingService, HoldCleanupService)
  currently still use `findByIdWithLock` on the event row. That is *fine* —
  they operate on HELD/BOOKED seats, not AVAILABLE ones, so they can never
  race with `holdSeats`. But if you add a new operation that flips seats
  from AVAILABLE to anything, either use a conditional UPDATE or reason
  through the concurrency implications carefully. Don't assume the event
  lock still protects you — it doesn't protect the hold path any more.
- **The diagnostic SELECT in `throwClaimFailure`** is an error-path-only
  cost. If it ever shows up in the happy-path hot path, something has
  regressed. It must not be hoisted into the success path.

---

## 9. Appendix: the original event-lock code for reference

For completeness, here is what `holdSeats` looked like before this refactor.
Reading it alongside the current version makes the simplification obvious:

```java
@Transactional
public HoldResponse holdSeats(Long eventId, HoldSeatsRequest request) {
    List<String> seatNumbers = request.getSeatNumbers();
    if (seatNumbers.size() != new HashSet<>(seatNumbers).size()) {
        throw new IllegalArgumentException("Duplicate seat numbers in request");
    }

    // Acquire pessimistic write lock on event row
    Event event = eventRepository.findByIdWithLock(eventId)
            .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));

    // Application-level duplicate-hold check (protected by the lock)
    if (seatHoldRepository.existsByEventIdAndUserIdAndStatus(
            eventId, request.getUserId(), HoldStatus.ACTIVE)) {
        throw new DuplicateHoldException(eventId, request.getUserId());
    }

    // Fetch seats and validate existence
    List<Seat> seats = seatRepository.findByEventIdAndSeatNumberIn(eventId, seatNumbers);
    Set<String> foundNumbers = seats.stream().map(Seat::getSeatNumber).collect(Collectors.toSet());
    List<String> missingSeats = seatNumbers.stream()
            .filter(num -> !foundNumbers.contains(num))
            .toList();
    if (!missingSeats.isEmpty()) {
        throw new InvalidSeatException(missingSeats);
    }

    // Application-level availability check (protected by the lock)
    List<String> unavailableSeats = seats.stream()
            .filter(s -> s.getStatus() != SeatStatus.AVAILABLE)
            .map(Seat::getSeatNumber)
            .toList();
    if (!unavailableSeats.isEmpty()) {
        throw new SeatsUnavailableException(unavailableSeats);
    }

    // Create hold and mark seats
    SeatHold hold = /* ... builder ... */;
    hold = seatHoldRepository.save(hold);
    for (Seat seat : seats) {
        seat.setStatus(SeatStatus.HELD);
        seat.setHold(hold);
    }
    seatRepository.saveAll(seats);

    return toHoldResponse(hold, seatNumbers);
}
```

Notice how much of this code *exists solely because of the coarse lock*: the
application-level duplicate check, the application-level availability check,
the explicit status mutation and `saveAll`. In the new design, the DB does
all of that work atomically, and the service method only has to *describe*
what it wants and interpret the result.

Less code. Fewer moving parts. Stronger correctness. Higher throughput. That's
the whole point.
