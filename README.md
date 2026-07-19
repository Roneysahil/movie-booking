# Movie Ticket Booking System

A seat-level movie ticket booking backend: multiple cities, theaters, screens and shows,
with time-bound seat holds, tiered pricing, discount codes, payment, and refunds under
configurable policies.

The engineering focus is the **booking funnel under contention** — many users racing for
the same seat, with money involved. Everything else is built to be complete but is not
where the depth went.

- **Stack:** Java 21, Spring Boot 4.1, PostgreSQL 17, Flyway, Spring Data JPA, Spring Security
- **Scale:** 53 REST endpoints, 20 tables, 55 tests
- **Design rationale:** [`docs/DESIGN.md`](docs/DESIGN.md)

---

## Quick start

**Prerequisites:** JDK 21 and PostgreSQL 17.

```bash
# macOS
brew install openjdk@21 postgresql@17
brew services start postgresql@17
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

createdb movie_booking
./mvnw spring-boot:run
```

Flyway creates the schema and loads demo data on first start. The app listens on `:8080`.

If your Postgres user is not your shell username, set `DB_USER` and `DB_PASSWORD`.

### Seeded logins

| Email | Password | Role |
|---|---|---|
| `admin@movies.test` | `admin123` | ADMIN |
| `customer@movies.test` | `customer123` | CUSTOMER |
| `priya@movies.test` | `customer123` | CUSTOMER |

A second customer exists so cross-account access can be tested.

### Demo data

3 cities · 4 theaters · 5 screens · 250 seats · 4 movies · **140 shows over 7 days** ·
7,000 show-seats · 3 refund policies · 6 discount codes.

Shows span a week deliberately, so every refund band is demonstrable without editing
data: a show 6 days out refunds 100%, one starting in 3 hours refunds nothing.

Three of the discount codes are intentionally unusable — `EXPIRED20` (window closed),
`SOLDOUT` (limit reached), `DISABLED` (inactive) — so failure paths can be exercised
immediately.

---

## Trying it out

The ids below are illustrative — take real ones from the responses as you go. Step 2
returns the `showSeatId` values that steps 3 onward need, and step 4 returns the
`bookingRef`.

```bash
# 1. browse
curl -su customer@movies.test:customer123 localhost:8080/api/cities
curl -su customer@movies.test:customer123 "localhost:8080/api/shows?cityId=1"

# 2. seat map — note showSeatId and per-seat resolved prices
curl -su customer@movies.test:customer123 localhost:8080/api/shows/63/seats

# 3. hold seats (5-minute window)
curl -su customer@movies.test:customer123 -X POST localhost:8080/api/holds \
  -H 'Content-Type: application/json' -d '{"showId":63,"showSeatIds":[4201,4202]}'

# 4. price it, with a discount
curl -su customer@movies.test:customer123 -X POST localhost:8080/api/bookings \
  -H 'Content-Type: application/json' -d '{"holdId":"1","discountCode":"FIRST50"}'

# 5. pay
curl -su customer@movies.test:customer123 -X POST localhost:8080/api/bookings/BK123456789/payment \
  -H 'Content-Type: application/json' -d '{"method":"CARD","idempotencyKey":"any-uuid"}'

# 6. cancel — refund resolved from the policy snapshotted at confirmation
curl -su customer@movies.test:customer123 -X POST localhost:8080/api/bookings/BK123456789/cancel
```

**To see the concurrency control**, race the same seat:

```bash
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "%{http_code}\n" -su customer@movies.test:customer123 \
    -X POST localhost:8080/api/holds -H 'Content-Type: application/json' \
    -d '{"showId":63,"showSeatIds":[4270]}' &
done; wait
# 201 once, 409 nine times
```

---

## How seat allocation works

This is the requirement everything else is judged against, so it is worth stating
precisely.

**The race:** two users select seat A5 within milliseconds. Both read it as available.
Both write. Without protection the seat is sold twice.

**The mechanism:** allocation is decided in exactly one place — `POST /api/holds` — by
taking exclusive row locks and re-checking status *while holding them*.

```sql
SELECT * FROM show_seats
 WHERE show_id = :showId AND id IN (:ids)
 ORDER BY id
   FOR UPDATE;
```

Three details carry the weight:

1. **`FOR UPDATE`** — the second transaction blocks at the `SELECT` until the first
   commits, then sees `HELD` and is rejected with `409` naming the seats.
2. **`ORDER BY id`** — not cosmetic. Two requests for overlapping selections (`{A5,A6}`
   and `{A6,A5}`) would otherwise lock in opposite order and deadlock.
3. **Validation happens after the lock.** Checking before locking is the classic
   time-of-check/time-of-use bug and defeats the mechanism entirely.

**Defence in depth.** A partial unique index guarantees the invariant even if the
application logic is wrong:

```sql
CREATE UNIQUE INDEX uq_show_seat_active_booking
    ON booking_seats (show_seat_id) WHERE active;
```

Row locks are an application-layer contract; the constraint is a hard guarantee.

**No lock is ever held across an external call.** The lock lives only for the duration of
hold acquisition — single-digit milliseconds — never across payment.

`SeatConcurrencyIntegrationTest` proves this with 20 real threads against real Postgres.

---

## Architecture

Layered, package-by-feature under `com.roneysahil.movie_booking`:

```
config/         security, scheduling
common/         domain exceptions, RFC 7807 handler
user/           accounts, DB-backed authentication
catalog/        cities, theaters, screens, seats, movies
show/           shows, show-seats, pricing tiers
booking/        holds, bookings, payments, sweeper
discount/       codes, redemptions
refund/         policies, refunds, policy snapshots
notification/   transactional outbox
```

Each feature is `domain/` (entities) + `repository/` + a service interface with a JPA
implementation + `web/` controllers and DTOs.

**Rules the code follows:** controllers contain no business logic; entities never leave
the service layer; all money is `BigDecimal` at scale 2; all instants are UTC; Flyway owns
the schema and Hibernate runs `ddl-auto=validate`, so a mapping that disagrees with the
SQL fails at startup rather than at first query.

### Key tables

`show_seats` is **the unit of allocation** — one row per seat per show, materialized when
the show is created. Materializing rather than deriving availability from bookings is what
makes locking a specific seat possible. It costs rows and buys a clean concurrency story;
that trade is the central design decision of this system.

Prices are stored three times — on `show_seats` (current), `booking_seats` (as charged),
and `shows.base_price`. Deliberate denormalization: repricing a tier tomorrow must not
change what a customer paid today, nor what they are refunded.

---

## API

All routes are under `/api`. Authentication is HTTP Basic on every request.

### Customer

```
POST   /api/auth/register                     public
GET    /api/auth/me

GET    /api/cities
GET    /api/cities/{id}/movies?q=             optional title filter
GET    /api/cities/{id}/theaters
GET    /api/movies/{id}
GET    /api/shows?cityId=&movieId=&theaterId=&date=
GET    /api/shows/{id}
GET    /api/shows/{id}/seats                  seat map with resolved prices

POST   /api/holds                             claim seats — 409 on conflict
GET    /api/holds/{id}                        remaining seconds
DELETE /api/holds/{id}

POST   /api/discounts/validate                preview, non-authoritative
POST   /api/bookings                          price the hold, apply discount
POST   /api/bookings/{ref}/payment            idempotency-keyed
GET    /api/bookings?status=                  history
GET    /api/bookings/{ref}
POST   /api/bookings/{ref}/cancel             → refund per snapshotted policy
GET    /api/bookings/{ref}/refund
```

### Admin

```
GET|POST|PUT|DELETE  /api/admin/cities[/{id}]
GET|POST|PUT|DELETE  /api/admin/theaters[/{id}]
PUT                  /api/admin/theaters/{id}/refund-policy
GET|POST|PUT|DELETE  /api/admin/movies[/{id}]
GET|POST             /api/admin/screens
GET|PUT              /api/admin/screens/{id}/layout
GET|POST|PUT|DELETE  /api/admin/shows[/{id}]
GET                  /api/admin/pricing
POST|PUT|DELETE      /api/admin/pricing/tiers[/{tier}]
PUT                  /api/admin/pricing/weekend
GET|POST|PUT|DELETE  /api/admin/refund-policies[/{id}]
PUT                  /api/admin/refund-policies/{id}/default
GET|POST             /api/admin/discounts
```

### Errors

RFC 7807 `ProblemDetail` throughout. No stack traces or internal messages reach clients.

| Status | Meaning |
|---|---|
| 400 | Validation failed — includes a per-field `errors` map |
| 401 | Missing or bad credentials |
| 403 | Wrong role |
| 404 | Not found, **or not yours** |
| 409 | Seat conflict — includes `unavailableSeatIds` |
| 422 | Business rule violation |

A 409 names the seats that were taken so a client can re-render the map — under load this
is an expected outcome, not an exceptional one.

---

## Pricing

Resolved server-side, in order. Client-supplied prices are ignored entirely.

1. **Base** — `shows.base_price`
2. **Seat tier** — REGULAR ×1.0, PREMIUM ×1.5, RECLINER ×2.0 (admin-configurable)
3. **Weekend surcharge** — ×1.2, evaluated in the *city's* timezone
4. → subtotal
5. **Discount code** — percent or flat, with cap and minimum-order floor
6. → total

Seat prices are computed once when a show is created and stored on `show_seats`.

---

## Refunds

Policies are named sets of bands keyed on hours before showtime. Seeded `STANDARD`:

| Hours before show | Refund |
|---|---|
| > 48 | 100% |
| 24–48 | 75% |
| 6–24 | 50% |
| < 6 | 0% |

Theaters may be assigned different policies (`FLEXIBLE` and `STRICT` are also seeded).

**The policy is snapshotted onto the booking as JSON at confirmation.** A later admin edit
cannot retroactively change terms a customer already agreed to. Every policy must define a
band at 0 hours, so no cancellation can fall through to an undefined refund.

---

## Notifications

Confirmation and cancellation notices go through a **transactional outbox**: the booking
transaction writes a `notification_outbox` row as part of its own commit, and delivery
happens separately.

Calling a mail provider inline would mean a provider timeout rolls back a paid booking —
trading a missing email for a lost sale. The outbox also survives restarts, which a
fire-and-forget `@Async` call does not. A unique constraint on `(booking_id, type)`
prevents duplicate sends.

Three pieces:

- **`NotificationPublisher`** — writes intent inside the booking transaction
- **`NotificationDispatcher`** — polls every 10s, delivers each row in its own
  transaction, retries with exponential backoff (1/2/4/8 min), parks a row as `FAILED`
  after 5 attempts
- **`ShowReminderScheduler`** — every 5 minutes, queues a reminder for confirmed bookings
  whose show starts within 24 hours; idempotent via a `NOT EXISTS` check plus the unique
  constraint

**Delivery itself is a logging stub** — `NotificationSender` is the only class that knows
how a message leaves the system, so swapping in SES or Twilio replaces that one class and
leaves the outbox, retry and backoff behaviour untouched.

---

## Testing

```bash
createdb movie_booking_test     # one-time
./mvnw test                     # 55 tests
```

Tests use a **separate database**, rebuilt from migrations and seed for each context.

| Suite | Tests | Covers |
|---|---|---|
| `SeatConcurrencyIntegrationTest` | 4 | 20 threads on one seat; overlapping selections; concurrent full funnels; two-customer race |
| `BookingFlowIntegrationTest` | 9 | Funnel over HTTP, 409, discount cap, unusable codes, payment idempotency, booking history |
| `AccessControlIntegrationTest` | 11 | Anonymous, bad credentials, registration, role separation, cross-customer access |
| `NotificationOutboxIntegrationTest` | 4 | Enqueue-then-deliver, retry with backoff, booking survives transport failure |
| `PricingRulesTest` | 26 | Discount maths, refund band boundaries, hold expiry |

The concurrency tests are deliberately **not** `@Transactional`: a test-managed
transaction would confine every thread to one connection and the race being tested could
not occur. They assert not just "one winner" but **zero failures for any other reason** —
which is what catches deadlocks and lock timeouts.

---

## Assumptions and decisions

Decisions made where the requirement was open, and the reasoning:

**Weekend is a day multiplier, not a seat tier.** The brief lists "regular, premium,
weekend" together. Weekend is a property of the showtime, not the seat; treating it as a
tier means a premium Saturday seat needs a fourth tier, then a fifth for premium-holiday,
and it combinatorially explodes.

**Seats reserve at booking creation, not at payment.** A customer entering card details
cannot lose their seat mid-checkout. The cost is abandoned bookings holding seats until the
sweeper reclaims them after 15 minutes.

**Hold expiry is lazy, with a sweeper for hygiene.** A held seat past `expires_at` reads as
available immediately; the scheduled job only tidies rows. If correctness depended on the
sweeper, a stalled scheduler would take inventory offline.

**HTTP Basic rather than JWT.** The brief puts advanced auth out of scope. A hand-rolled
JWT filter would consume hours better spent on concurrency and would be *worse* than Basic
here — a self-issued token with no rotation or revocation is a liability. The auth
mechanism is one bean; the authorization model is the part that matters and is fully built.

**404, not 403, for another user's booking.** A 403 confirms the reference exists, making
booking references enumerable.

**Changing a pricing tier does not reprice existing shows.** Two customers paying different
amounts for identical seats with no explanation is worse than slight staleness. New shows
pick up the new multiplier.

**Everything soft-deletes.** Hard-deleting a city with theaters, shows and paid bookings
would orphan real money.

**Shows cannot move between screens.** It would orphan the materialized `show_seats`;
cancel and recreate instead.

**Search is not implemented.** The requirement says customers "browse shows". Filtering by
city, movie, theater and date is what makes browse usable and is built; full-text search,
ranking and autocomplete are scope creep. A simple `q` title filter is included as a
convenience.

**Registration creates CUSTOMER only.** Admins are provisioned, never self-registered.

---

## Out of scope

| Not built | Reason |
|---|---|
| Real payment gateway | Simulated deterministically. Integrating Stripe demonstrates reading docs, not system design |
| Real email/SMS delivery | Outbox and schema are real; the transport is a stub |
| UI / frontend | Explicitly out of scope |
| Deployment, containers, CI | Explicitly out of scope |
| Microservices | Explicitly out of scope, and unjustifiable at this size |
| Dynamic / surge pricing | Tiers plus day multipliers already exercise the pricing engine |
| Caching | Premature — no measured bottleneck |
| Waitlists, group bookings, loyalty | Scope control. The funnel is deeper than it is wide, by choice |

## Known limitations

- **The sweeper and any scheduled job assume a single instance.** Multi-instance would need
  ShedLock or Postgres advisory locks. The seam is isolated to one class.
- **Cancelling a show does not auto-refund its confirmed bookings.** It closes the show;
  refunds remain customer-initiated.
- **No rate limiting on hold creation** — a malicious user could hold many seats. The real
  mitigation is a per-user concurrent-hold cap.
- **Materializing `show_seats` makes show creation O(seats).** Fine at this scale; a very
  large venue scheduled far ahead would want batched materialization.
- **Payment is simulated**, so there is no reconciliation against a provider ledger.

---

## AI workflow

Built with Claude Code. The working method, since the brief asks:

- [`CLAUDE.md`](CLAUDE.md) was written **before any feature code** and pins the stack,
  package layout, conventions, and four non-negotiable correctness properties. It is
  context for every subsequent session, not documentation written afterwards.
- [`docs/DESIGN.md`](docs/DESIGN.md) records the domain model, seat lifecycle, and
  concurrency strategy — also written before implementation, so the code had a spec to
  meet rather than a rationalisation to fit.
- The API surface was built against **in-memory stubs first**, so the contract and error
  semantics could be reviewed independently of persistence. Stubs were then replaced by
  JPA implementations one feature at a time, with the honest gaps documented in javadoc
  as they went.
- Every claim in this README was verified by running the code — the server was started and
  driven with real requests, not inferred from the source.

No Claude Code skills were used; the work was direct implementation rather than any
packaged workflow.
