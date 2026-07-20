# Demo script (video sections 3 and 7)

Paste-ready commands for the recording. IDs are resolved from the API at run time, so
nothing hard-codes a show or seat id that could be stale on camera.

Every block is verified against a freshly seeded database.

---

## Prep (before recording — not on camera)

Reset to a pristine seed so the demo is clean and repeatable:

```bash
brew services start postgresql@17
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
dropdb movie_booking && createdb movie_booking
./mvnw spring-boot:run
```

Wait for `Started MovieBookingApplication`. In a second terminal, set up the session:

```bash
B=http://localhost:8080
U=customer@movies.test:customer123
```

Keep the font large. Each block below is one paste.

---

## Section 3 — the booking funnel

**1. Browse cities**

```bash
curl -s -u $U $B/api/cities | python3 -m json.tool
```

**2. Shows in a city** — point out `fromPrice` (cheapest tier) and weekend vs weekday

```bash
curl -s -u $U "$B/api/shows?cityId=1" | python3 -c "
import sys,json; d=json.load(sys.stdin)
print(len(d),'shows')
[print(s['id'], s['movieTitle'], '|', s['startsAt'], '| from Rs', s['fromPrice']) for s in d[:5]]"
```

**3. Pick a show and open its seat map** — resolved prices per seat, per tier

```bash
SHOW=$(curl -s -u $U "$B/api/shows" | python3 -c "import sys,json;d=json.load(sys.stdin);print([s for s in d if s['startsAt']>'2026-07-23'][0]['id'])")
curl -s -u $U $B/api/shows/$SHOW/seats | python3 -c "
import sys,json; d=json.load(sys.stdin)
print('available:', d['availableCount'], 'of', len(d['seats']))
for s in d['seats'][:2] + d['seats'][16:18]:
    print(' ', s['showSeatId'], s['rowLabel']+str(s['seatNumber']), s['tier'], 'Rs', s['price'], s['status'])"
```

> Narrate: "`showSeatId` is what every booking call references from here. Prices are
> resolved server-side — base × tier × weekend — the client never sends a price."

**4. Hold two seats** (5-minute window) — captures the hold id into `HID`

```bash
SEATS=$(curl -s -u $U $B/api/shows/$SHOW/seats | python3 -c "import sys,json;d=json.load(sys.stdin);print(','.join(str(s['showSeatId']) for s in d['seats'] if s['status']=='AVAILABLE')[:0] or ','.join(str(s['showSeatId']) for s in d['seats'] if s['status']=='AVAILABLE')[:9])")
HID=$(curl -s -u $U -X POST $B/api/holds -H 'Content-Type: application/json' \
  -d "{\"showId\":$SHOW,\"showSeatIds\":[$SEATS]}" | tee /dev/stderr | python3 -c "import sys,json;print(json.load(sys.stdin)['holdId'])")
echo "hold id: $HID"
```

**5. Book with a discount** — FIRST50 is 50% capped at ₹150, so it caps out. Captures `REF`

```bash
REF=$(curl -s -u $U -X POST $B/api/bookings -H 'Content-Type: application/json' \
  -d "{\"holdId\":\"$HID\",\"discountCode\":\"FIRST50\"}" | tee /dev/stderr | python3 -c "import sys,json;print(json.load(sys.stdin)['bookingRef'])")
echo "booking ref: $REF"
```

> Narrate: "No money has moved yet — this is a priced, itemized booking to review. The
> discount capped at 150 even though 50% of the subtotal is more."

**6. Pay** — idempotency-keyed

```bash
curl -s -u $U -X POST $B/api/bookings/$REF/payment -H 'Content-Type: application/json' \
  -d '{"method":"CARD","idempotencyKey":"demo-key-1"}' | python3 -c "import sys,json;print('status:', json.load(sys.stdin)['status'])"
```

**7. Cancel** — refund from the snapshotted policy

```bash
curl -s -u $U -X POST $B/api/bookings/$REF/cancel | python3 -m json.tool
```

> Narrate: "The refund percent comes from the policy snapshotted onto the booking at
> confirmation — not the live policy. An admin editing terms today can't change what this
> customer already agreed to."

---

## Section 7 — concurrency (the centerpiece)

**Race 10 requests for one seat.** Run it, let the tally land, then explain.

```bash
SHOW=$(curl -s -u $U "$B/api/shows" | python3 -c "import sys,json;d=json.load(sys.stdin);print([s for s in d if s['startsAt']>'2026-07-23'][0]['id'])")
SEAT=$(curl -s -u $U $B/api/shows/$SHOW/seats | python3 -c "import sys,json;d=json.load(sys.stdin);print([s['showSeatId'] for s in d['seats'] if s['status']=='AVAILABLE'][0])")

for i in $(seq 1 10); do
  curl -s -o /dev/null -w "%{http_code}\n" -u $U -X POST $B/api/holds \
    -H 'Content-Type: application/json' -d "{\"showId\":$SHOW,\"showSeatIds\":[$SEAT]}" &
done | sort | uniq -c
wait
```

Expected output:

```
   1 201
   9 409
```

> Narrate, in order:
> 1. "One winner, nine clean 409s — no double-allocation, no errors."
> 2. Open `ShowSeatRepository.lockSeatsForUpdate` — "`SELECT ... FOR UPDATE`: the losers
>    block until the winner commits, then see the seat is HELD."
> 3. "`ORDER BY id` isn't cosmetic — it stops two overlapping multi-seat requests locking
>    in opposite order and deadlocking."
> 4. Open `JpaBookingService.createHold` — "status is re-checked *after* the lock. Checking
>    before is a time-of-check/time-of-use bug that defeats the whole mechanism."
> 5. Open `V1__baseline.sql`, `uq_show_seat_active_booking` — "and a partial unique index
>    is the hard backstop: even if the app logic were wrong, the database physically can't
>    put one seat in two active bookings. Locks are the contract; the constraint is the
>    guarantee."

**Optional — show the constraint refusing a double-allocation directly** (psql, very punchy):

```bash
psql movie_booking -c "\
INSERT INTO booking_seats (booking_id, show_seat_id, price) \
SELECT booking_id, show_seat_id, price FROM booking_seats WHERE active LIMIT 1;"
# ERROR: duplicate key value violates unique constraint "uq_show_seat_active_booking"
```

---

## Fallback if a command misbehaves on camera

The concurrency proof also runs as a test — if the live race stalls, show this instead:

```bash
./mvnw test -Dtest=SeatConcurrencyIntegrationTest
```

Four tests, green: 20-thread race, overlapping selections (deadlock check), concurrent full
bookings, two-customer race.
