# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

A movie ticket booking system: multiple cities, theaters per city, screens per theater,
shows per screen, and seat-level booking with time-bound holds. Built as an SDE-2 take-home.

Read `docs/DESIGN.md` before making non-trivial changes. It records the domain model,
the seat-hold state machine, and the concurrency strategy — the decisions the rest of
the code depends on.

## Stack

- Java 21, Spring Boot 4.1
- Spring Web MVC, Data JPA, Security, Validation, Actuator
- PostgreSQL 17 + Flyway migrations
- Lombok
- JUnit 5 + MockMvc

## Commands

```bash
./mvnw spring-boot:run          # run the app on :8080
./mvnw test                     # unit + integration tests
./mvnw verify                   # full build
./mvnw test -Dtest=ClassName    # single test class
```

`JAVA_HOME` must point at a JDK 21. With the Homebrew install:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

Local database (expected by `application.properties`):

```bash
createdb movie_booking
```

## Architecture

Layered, package-by-feature under `com.roneysahil.movie_booking`:

```
config/          security, jackson, scheduling, async
common/          error handling, base entities, shared value objects
catalog/         city, theater, screen, seat, movie  (admin-managed)
show/            show, show_seat, pricing
booking/         hold, booking, payment, refund
discount/        discount codes and redemption
notification/    outbox + async dispatch
user/            accounts, roles, auth
```

Within a feature: `domain/` (entities, enums), `repository/`, `service/`, `web/`
(controllers + DTOs), `mapper/`.

Rules:
- Controllers do no business logic. Validate, delegate, map to DTO.
- Entities never leave the service layer — always map to DTOs.
- All money is `BigDecimal` with scale 2. Never `double`.
- All timestamps are `Instant`, stored UTC. Cities carry a timezone for display only.
- Schema changes go in a new Flyway migration. Never edit an applied migration.
- `spring.jpa.hibernate.ddl-auto=validate`. Flyway owns the schema.

## Non-negotiables

These are the requirements the submission is judged on. Do not regress them.

1. **Seat allocation must serialize correctly.** Concurrent bookings of the same seat
   must never both succeed. The mechanism is pessimistic row locking on `show_seat`
   with deterministic lock ordering — see `docs/DESIGN.md`. Any change here needs a
   concurrent integration test proving no double-allocation.
2. **Holds expire.** A hold past `expires_at` is treated as released on read; a
   scheduled sweeper reconciles rows. Correctness comes from the lazy check, not the sweeper.
3. **Notifications never block booking.** They go through an outbox and are dispatched
   asynchronously. A failing notification must not fail or roll back a booking.
4. **Money is computed server-side.** Client-supplied prices are ignored.

## Testing

- Unit tests for pricing, discount, and refund calculation — pure logic, no Spring context.
- `@SpringBootTest` + MockMvc for API flows including authz (customer hitting an admin
  endpoint must get 403).
- Concurrency tests use a real thread pool against a real Postgres schema. H2 does not
  reproduce `SELECT FOR UPDATE` semantics faithfully — do not substitute it here.
- Every bug fix gets a regression test.

## Conventions

- Error responses use RFC 7807 `ProblemDetail`. No stack traces or raw exception
  messages in responses.
- Business rule violations throw domain exceptions handled by `@RestControllerAdvice`,
  mapped to 4xx. Unexpected exceptions are 500 and logged with a correlation id.
- Prefer constructor injection. No field `@Autowired`.
- Keep commits scoped to one logical change with a descriptive message.
