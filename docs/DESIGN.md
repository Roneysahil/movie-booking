# Design

Scoping decisions, domain model, and the reasoning behind them. The product requirement
was deliberately open-ended; this document records what was built, what was deliberately
left out, and why.

## 1. Interpretation

The requirement describes a BookMyShow-style system. The interesting engineering is not
the CRUD around cities and theaters — it is the **booking funnel under contention**:
many users racing for the same seat on the same show, with money involved. That is where
the depth goes. Catalog management is built to be complete but unglamorous.

Three properties are treated as correctness requirements rather than features:

1. A seat is allocated to exactly one confirmed booking. Ever.
2. A hold that expires releases its seats without human intervention.
3. A notification failure never costs a customer their booking.

## 2. Domain model

```
City ──< Theater ──< Screen ──< Seat
                       │
Movie ──────────────< Show >── ShowSeat ──> Seat
                       │           │
                       │           └──< BookingSeat >── Booking
                       │                                  │
                    SeatHold ─────────────────────────────┤
                                                          ├── Payment
                                                          ├── Refund
                                                          └── DiscountRedemption
```

### Catalog (admin-owned)

| Entity | Notes |
|---|---|
| `City` | name, state, IANA timezone |
| `Theater` | belongs to a city; address |
| `Screen` | belongs to a theater; a physical auditorium |
| `Seat` | belongs to a screen; `row_label`, `seat_number`, `tier`. The *physical* seat |
| `Movie` | title, duration, language, certification |

### Scheduling

| Entity | Notes |
|---|---|
| `Show` | a `Movie` on a `Screen` at a time. `starts_at`, `ends_at`, `base_price`, status |
| `ShowSeat` | **the unit of allocation.** One row per (show, seat). Status, price, hold ref |

`ShowSeat` is the single most important table. Materializing a row per seat per show at
show-creation time — rather than deriving availability by querying bookings — is what
makes locking a specific seat possible. It costs rows (a 200-seat screen × 5 shows/day =
1000 rows/day/screen) and buys a clean concurrency story. That tradeoff is the central
design decision of this system.

### Booking

| Entity | Notes |
|---|---|
| `SeatHold` | a time-bound claim over N show-seats by one user. `expires_at` |
| `Booking` | `booking_ref`, user, show, status, subtotal, discount, total |
| `BookingSeat` | join row; captures price **at time of booking** |
| `Payment` | amount, status, provider ref, `idempotency_key` |
| `Refund` | amount, status, the policy applied |

Prices are snapshotted onto `BookingSeat`. An admin editing pricing tomorrow must not
retroactively change what a customer paid today, nor what they are refunded.

### Supporting

`User` (BCrypt hash, role), `DiscountCode`, `DiscountRedemption`, `RefundPolicy`,
`NotificationOutbox`.

## 3. Seat lifecycle

`ShowSeat.status` is the state machine. It is the only thing that determines availability.

```
                  hold created
     AVAILABLE ───────────────────> HELD
         ^                           │ │
         │  hold expired /           │ │  payment succeeded
         │  released /               │ │
         │  payment failed           │ └──────────────> BOOKED
         └───────────────────────────┘                    │
         │                                                │
         └────────────────────────────────────────────────┘
                        booking cancelled
```

**Expiry is lazy, with a sweeper for hygiene.** A `HELD` seat whose hold is past
`expires_at` is treated as `AVAILABLE` by every read and by the acquisition query.
A `@Scheduled` job then flips such rows back and marks holds `EXPIRED`.

This matters: if correctness depended on the sweeper, a paused or lagging scheduler would
make seats unbookable for as long as it was down. The sweeper is an optimization that keeps
the table tidy and queries simple — never the source of truth. This is the kind of detail
that separates a system that works in a demo from one that works.

## 4. Concurrency

**The race:** two users select seat A5 for show 42 within milliseconds. Both read it as
available. Both write. Without protection, both succeed — the seat is double-sold, and one
customer finds a stranger sitting in their seat.

**The mechanism: pessimistic row locks, acquired in a deterministic order.**

```sql
SELECT * FROM show_seat
 WHERE show_id = :showId AND id IN (:seatIds)
 ORDER BY id
   FOR UPDATE;
```

- `FOR UPDATE` takes exclusive row locks. The second transaction blocks at the `SELECT`
  until the first commits, then observes the seat as `HELD` and is rejected cleanly with 409.
- `ORDER BY id` is **not cosmetic — it prevents deadlock.** Two requests for overlapping
  multi-seat selections (`{A5,A6}` and `{A6,A5}`) would otherwise grab locks in opposite
  order and deadlock. Consistent ordering makes that impossible.
- Statuses are re-validated *after* the lock is held. Checking before locking is the
  classic TOCTOU bug and defeats the entire mechanism.

**Defense in depth.** A partial unique index guarantees the invariant at the database
level even if application logic is wrong:

```sql
CREATE UNIQUE INDEX uq_show_seat_active_booking
    ON booking_seat (show_seat_id)
 WHERE active;
```

Locks are an application-layer contract; the constraint is a hard guarantee. Both are cheap.

**Why pessimistic over optimistic.** Optimistic `@Version` locking makes contention the
loser's problem: they do the work, then fail at commit and must retry. For seat booking,
contention is the *expected* case for desirable seats, so retry storms would be common and
the failure surfaces late — after the user has entered payment details. Pessimistic locking
makes the loser wait milliseconds and fail fast, before payment. The lock is held only for
the duration of the hold-acquisition transaction (single-digit ms), never across the payment
call. Holding a database lock while waiting on a payment gateway would be indefensible.

**Why not application-level or distributed locks.** Single-node `synchronized` breaks the
moment a second instance runs. Redis locks add an external dependency and, done properly,
a fencing story — unjustified when the database already provides exactly this primitive
transactionally. Distributed systems are explicitly out of scope.

## 5. Pricing

Resolved server-side, in order. Client-supplied prices are ignored entirely.

1. **Base** — `show.base_price`
2. **Tier multiplier** — `REGULAR` ×1.0, `PREMIUM` ×1.5, `RECLINER` ×2.0
3. **Day multiplier** — weekend/holiday surcharge, configurable per show
4. → **subtotal** (sum over selected seats)
5. **Discount code** — percentage or flat, with `max_discount` cap and `min_amount` floor
6. → **total**

Discount codes validate on: active flag, date window, minimum order, global usage limit,
and per-user limit. Redemption is recorded in `DiscountRedemption` and counted inside the
booking transaction — otherwise a limited code races the same way seats do.

## 6. Refunds

`RefundPolicy` is a set of tiers keyed on hours before showtime, configurable per theater
with a system default:

| Hours before show | Refund |
|---|---|
| > 48 | 100% |
| 24–48 | 75% |
| 6–24 | 50% |
| < 6 | 0% |

The policy is resolved and **snapshotted onto the booking at confirmation time**, so a
policy edit cannot retroactively change the terms a customer already agreed to.
Cancellation releases seats back to `AVAILABLE` in the same transaction as the refund record.

## 7. Notifications

Booking confirmation and showtime reminders must not block or endanger the booking.

**Transactional outbox.** The booking transaction writes a `NotificationOutbox` row as part
of its own commit. A scheduled dispatcher picks up pending rows and delivers them with
retry and exponential backoff.

Calling an email provider inline inside the booking transaction would mean a provider
timeout rolls back a paid booking — trading a missing email for a lost sale. The outbox
also survives restarts, which a fire-and-forget `@Async` call does not. Delivery itself is
a logging stub; the *plumbing* is the point, and swapping in SES is a one-class change.

Reminders are a scheduled query for shows starting within the reminder window, deduplicated
by `(booking_id, type)`.

## 8. Security

Two roles, `ADMIN` and `CUSTOMER`, enforced with `@PreAuthorize` at the service boundary
plus URL rules. Passwords are BCrypt. Customers can read and mutate only their own bookings —
enforced by ownership checks in the service, not merely by role, since role alone would let
any customer cancel any other customer's booking by guessing an id.

**HTTP Basic over stateless sessions**, deliberately. The brief puts OAuth/SSO/MFA out of
scope. A hand-rolled JWT filter would consume hours better spent on booking concurrency, and
would be *worse* than Basic in this context — self-issued JWTs without rotation or
revocation are a liability, not a credential. The auth mechanism is swappable in one config
class; the authorization model is the part that actually matters and it is fully built.

## 9. Deliberately out of scope

| Not built | Reason |
|---|---|
| Real payment gateway | Simulated with deterministic outcomes for testing. Integrating Stripe demonstrates reading docs, not system design |
| Real email/SMS delivery | Outbox and dispatcher are real; the transport is a stub |
| Seat-map geometry / UI | Frontend explicitly out of scope. Seats carry row/number, not x-y coordinates |
| Microservices | Explicitly out of scope, and unjustifiable at this size |
| Dynamic/surge pricing | Interesting, but tiers + day multipliers already exercise the pricing engine |
| Caching layer | Premature. No measured bottleneck |
| Waitlists, group bookings, loyalty points | Scope control. The funnel is deeper than it is wide, by choice |

## 10. Known limitations

- `ShowSeat` materialization makes show creation O(seats). Fine at this scale; a very large
  venue with far-future scheduling would want batched or lazy materialization.
- The sweeper and reminder jobs assume a single instance. Multi-instance would need
  `ShedLock` or advisory locks — out of scope, but the seam is isolated to two classes.
- Payment is simulated, so no reconciliation against a real provider ledger.
- No rate limiting on hold creation; a malicious user could hold many seats. Real mitigation
  would be per-user concurrent-hold caps — noted, not built.
