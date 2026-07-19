-- Baseline schema for the movie ticket booking system.
--
-- Conventions:
--   * identity columns via GENERATED ALWAYS AS IDENTITY
--   * all instants are timestamptz, stored UTC
--   * all money is numeric(10,2) — never floating point
--   * enums are CHECK constraints rather than native types, so adding a value
--     is an ordinary migration instead of an ALTER TYPE
--   * entities soft-delete via `active`; hard deletes would orphan paid bookings

-- Required for the EXCLUDE constraint preventing overlapping shows on a screen.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------- identity --

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    full_name     TEXT        NOT NULL,
    role          TEXT        NOT NULL CHECK (role IN ('ADMIN', 'CUSTOMER')),
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------- refund policies --
-- Declared before theaters, which reference them.

CREATE TABLE refund_policies (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT        NOT NULL UNIQUE,
    is_default BOOLEAN     NOT NULL DEFAULT FALSE,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- At most one policy may be the system default.
CREATE UNIQUE INDEX uq_refund_policy_default
    ON refund_policies (is_default)
 WHERE is_default;

CREATE TABLE refund_policy_tiers (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    refund_policy_id      BIGINT   NOT NULL REFERENCES refund_policies (id) ON DELETE CASCADE,
    min_hours_before_show INTEGER  NOT NULL CHECK (min_hours_before_show >= 0),
    refund_percent        INTEGER  NOT NULL CHECK (refund_percent BETWEEN 0 AND 100),
    CONSTRAINT uq_policy_tier_hours UNIQUE (refund_policy_id, min_hours_before_show)
);

-- ----------------------------------------------------------------- catalog --

CREATE TABLE cities (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT        NOT NULL,
    state      TEXT        NOT NULL,
    timezone   TEXT        NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_city_name_state UNIQUE (name, state)
);

CREATE TABLE theaters (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    city_id          BIGINT      NOT NULL REFERENCES cities (id),
    refund_policy_id BIGINT               REFERENCES refund_policies (id),
    name             TEXT        NOT NULL,
    address          TEXT        NOT NULL,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_theater_city ON theaters (city_id) WHERE active;

CREATE TABLE screens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    theater_id BIGINT      NOT NULL REFERENCES theaters (id),
    name       TEXT        NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_screen_name_per_theater UNIQUE (theater_id, name)
);

-- Physical seats. These belong to a screen and exist independently of any show.
CREATE TABLE seats (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    screen_id   BIGINT  NOT NULL REFERENCES screens (id),
    row_label   TEXT    NOT NULL,
    seat_number INTEGER NOT NULL CHECK (seat_number > 0),
    tier        TEXT    NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_seat_position UNIQUE (screen_id, row_label, seat_number)
);

CREATE INDEX idx_seat_screen ON seats (screen_id);

CREATE TABLE movies (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title            TEXT        NOT NULL,
    duration_minutes INTEGER     NOT NULL CHECK (duration_minutes > 0),
    language         TEXT        NOT NULL,
    certification    TEXT        NOT NULL,
    synopsis         TEXT,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Case-insensitive title search for the browse filter.
CREATE INDEX idx_movie_title ON movies (lower(title));

-- ----------------------------------------------------------------- pricing --

CREATE TABLE pricing_tiers (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tier       TEXT          NOT NULL UNIQUE,
    multiplier NUMERIC(6, 3) NOT NULL CHECK (multiplier > 0),
    active     BOOLEAN       NOT NULL DEFAULT TRUE
);

-- Single-row configuration table; the CHECK enforces the singleton.
CREATE TABLE pricing_config (
    id                 SMALLINT      PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    weekend_multiplier NUMERIC(6, 3) NOT NULL CHECK (weekend_multiplier > 0),
    default_base_price NUMERIC(10, 2) NOT NULL CHECK (default_base_price >= 0),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- -------------------------------------------------------------- scheduling --

CREATE TABLE shows (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    movie_id   BIGINT         NOT NULL REFERENCES movies (id),
    screen_id  BIGINT         NOT NULL REFERENCES screens (id),
    starts_at  TIMESTAMPTZ    NOT NULL,
    ends_at    TIMESTAMPTZ    NOT NULL,
    base_price NUMERIC(10, 2) NOT NULL CHECK (base_price >= 0),
    status     TEXT           NOT NULL DEFAULT 'SCHEDULED'
                              CHECK (status IN ('SCHEDULED', 'CANCELLED', 'COMPLETED')),
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_show_time_order CHECK (ends_at > starts_at)
);

-- A screen cannot run two shows at once. Cancelled shows are exempt.
ALTER TABLE shows
    ADD CONSTRAINT ex_show_no_overlap
    EXCLUDE USING gist (
        screen_id WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (status <> 'CANCELLED');

CREATE INDEX idx_show_starts_at ON shows (starts_at) WHERE status = 'SCHEDULED';
CREATE INDEX idx_show_movie ON shows (movie_id, starts_at);

-- A customer's time-bound claim over seats on a show.
CREATE TABLE seat_holds (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    show_id    BIGINT      NOT NULL REFERENCES shows (id),
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    status     TEXT        NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE', 'CONVERTED', 'EXPIRED', 'RELEASED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Drives the expiry sweeper.
CREATE INDEX idx_hold_expiry ON seat_holds (expires_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_hold_user ON seat_holds (user_id, created_at DESC);

-- THE UNIT OF ALLOCATION. One row per seat per show, materialized when the show is
-- created. This is what makes SELECT ... FOR UPDATE on a specific seat possible.
CREATE TABLE show_seats (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    show_id BIGINT         NOT NULL REFERENCES shows (id),
    seat_id BIGINT         NOT NULL REFERENCES seats (id),
    hold_id BIGINT                  REFERENCES seat_holds (id),
    status  TEXT           NOT NULL DEFAULT 'AVAILABLE'
                           CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    price   NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    version INTEGER        NOT NULL DEFAULT 0,
    CONSTRAINT uq_show_seat UNIQUE (show_id, seat_id)
);

-- Seat-map reads and the availability count.
CREATE INDEX idx_show_seat_status ON show_seats (show_id, status);

-- ----------------------------------------------------------------- discounts --

CREATE TABLE discount_codes (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             TEXT           NOT NULL UNIQUE,
    type             TEXT           NOT NULL CHECK (type IN ('PERCENT', 'FLAT')),
    value            NUMERIC(10, 2) NOT NULL CHECK (value > 0),
    max_discount     NUMERIC(10, 2)          CHECK (max_discount IS NULL OR max_discount > 0),
    min_order_amount NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (min_order_amount >= 0),
    valid_from       TIMESTAMPTZ    NOT NULL,
    valid_to         TIMESTAMPTZ    NOT NULL,
    usage_limit      INTEGER                 CHECK (usage_limit IS NULL OR usage_limit > 0),
    times_used       INTEGER        NOT NULL DEFAULT 0 CHECK (times_used >= 0),
    per_user_limit   INTEGER                 CHECK (per_user_limit IS NULL OR per_user_limit > 0),
    active           BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_discount_validity CHECK (valid_to > valid_from),
    -- A percentage over 100 would pay the customer to attend.
    CONSTRAINT ck_percent_range CHECK (type <> 'PERCENT' OR value <= 100),
    CONSTRAINT ck_usage_within_limit CHECK (usage_limit IS NULL OR times_used <= usage_limit)
);

-- ------------------------------------------------------------------ booking --

CREATE TABLE bookings (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booking_ref            TEXT           NOT NULL UNIQUE,
    user_id                BIGINT         NOT NULL REFERENCES users (id),
    show_id                BIGINT         NOT NULL REFERENCES shows (id),
    discount_code_id       BIGINT                  REFERENCES discount_codes (id),
    status                 TEXT           NOT NULL DEFAULT 'PENDING_PAYMENT'
                                          CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED',
                                                            'CANCELLED', 'EXPIRED')),
    subtotal               NUMERIC(10, 2) NOT NULL CHECK (subtotal >= 0),
    discount_amount        NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    total_amount           NUMERIC(10, 2) NOT NULL CHECK (total_amount >= 0),
    -- Refund terms frozen at confirmation. A foreign key would let a later policy
    -- edit retroactively change what the customer agreed to.
    refund_policy_snapshot JSONB,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_discount_not_exceeding CHECK (discount_amount <= subtotal),
    CONSTRAINT ck_total_consistent CHECK (total_amount = subtotal - discount_amount)
);

CREATE INDEX idx_booking_user ON bookings (user_id, created_at DESC);
CREATE INDEX idx_booking_show ON bookings (show_id);

CREATE TABLE booking_seats (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booking_id   BIGINT         NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    show_seat_id BIGINT         NOT NULL REFERENCES show_seats (id),
    -- Price as charged. Repricing a tier tomorrow must not change what was paid today.
    price        NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    active       BOOLEAN        NOT NULL DEFAULT TRUE
);

-- THE DOUBLE-BOOKING GUARANTEE. Row locks are an application-layer contract; this is a
-- hard one. Even with buggy locking code, a seat cannot land in two live bookings.
CREATE UNIQUE INDEX uq_show_seat_active_booking
    ON booking_seats (show_seat_id)
 WHERE active;

CREATE INDEX idx_booking_seat_booking ON booking_seats (booking_id);

CREATE TABLE payments (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booking_id      BIGINT         NOT NULL REFERENCES bookings (id),
    amount          NUMERIC(10, 2) NOT NULL CHECK (amount >= 0),
    status          TEXT           NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    method          TEXT           NOT NULL,
    provider_ref    TEXT,
    -- Makes a retried payment safe: the second attempt collides instead of charging twice.
    idempotency_key TEXT           NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_booking ON payments (booking_id);

CREATE TABLE refunds (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booking_id     BIGINT         NOT NULL REFERENCES bookings (id),
    amount         NUMERIC(10, 2) NOT NULL CHECK (amount >= 0),
    refund_percent INTEGER        NOT NULL CHECK (refund_percent BETWEEN 0 AND 100),
    policy_name    TEXT           NOT NULL,
    status         TEXT           NOT NULL CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- One refund per booking; partial refunds are out of scope.
    CONSTRAINT uq_refund_booking UNIQUE (booking_id)
);

CREATE TABLE discount_redemptions (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    discount_code_id BIGINT      NOT NULL REFERENCES discount_codes (id),
    user_id          BIGINT      NOT NULL REFERENCES users (id),
    booking_id       BIGINT      NOT NULL REFERENCES bookings (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_redemption_per_booking UNIQUE (discount_code_id, booking_id)
);

-- Supports the per-user limit check.
CREATE INDEX idx_redemption_user ON discount_redemptions (discount_code_id, user_id);

-- ------------------------------------------------------------ notifications --

-- Transactional outbox. Written in the same transaction as the booking, dispatched
-- asynchronously, so a mail provider outage can never roll back a paid booking.
CREATE TABLE notification_outbox (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booking_id      BIGINT      REFERENCES bookings (id),
    type            TEXT        NOT NULL
                                CHECK (type IN ('BOOKING_CONFIRMATION', 'SHOW_REMINDER',
                                                'REFUND_PROCESSED', 'BOOKING_CANCELLED')),
    recipient       TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Prevents a retry storm or duplicate scheduler run from double-sending.
    CONSTRAINT uq_outbox_booking_type UNIQUE (booking_id, type)
);

-- Drives the dispatcher poll.
CREATE INDEX idx_outbox_pending
    ON notification_outbox (next_attempt_at)
 WHERE status = 'PENDING';
