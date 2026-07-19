-- Demo data. Lives in db/seed rather than db/migration so it can be excluded by
-- narrowing spring.flyway.locations to classpath:db/migration alone.
--
-- Seeded logins (BCrypt, cost 10):
--   admin@movies.test    / admin123
--   customer@movies.test / customer123
--   priya@movies.test    / customer123

-- ------------------------------------------------------------ refund policies --

INSERT INTO refund_policies (name, is_default, active) VALUES
    ('STANDARD', TRUE,  TRUE),
    ('FLEXIBLE', FALSE, TRUE),
    ('STRICT',   FALSE, TRUE);

INSERT INTO refund_policy_tiers (refund_policy_id, min_hours_before_show, refund_percent) VALUES
    -- STANDARD
    (1, 48, 100), (1, 24, 75), (1, 6, 50), (1, 0, 0),
    -- FLEXIBLE: full refund right up to showtime
    (2, 2, 100), (2, 0, 50),
    -- STRICT: nothing inside two days
    (3, 72, 100), (3, 48, 50), (3, 0, 0);

-- ------------------------------------------------------------------- pricing --

INSERT INTO pricing_tiers (tier, multiplier, active) VALUES
    ('REGULAR',  1.000, TRUE),
    ('PREMIUM',  1.500, TRUE),
    ('RECLINER', 2.000, TRUE);

INSERT INTO pricing_config (id, weekend_multiplier, default_base_price)
VALUES (1, 1.200, 250.00);

-- --------------------------------------------------------------------- users --

INSERT INTO users (email, password_hash, full_name, role, active) VALUES
    ('admin@movies.test',    '$2a$10$d4otCN4NRXAXeep4nrluuuN7iu/sLej81SeBy2bpwGHcSCfVfaAiW', 'Admin User',  'ADMIN',    TRUE),
    ('customer@movies.test', '$2a$10$j4xE4sVAZHm360U2lfQg5eW2ajc7yncA.tdN8b1goEmsqEfx5bGbK', 'Test Customer', 'CUSTOMER', TRUE),
    ('priya@movies.test',    '$2a$10$j4xE4sVAZHm360U2lfQg5eW2ajc7yncA.tdN8b1goEmsqEfx5bGbK', 'Priya Nair',  'CUSTOMER', TRUE);

-- ------------------------------------------------------------------- catalog --

INSERT INTO cities (name, state, timezone) VALUES
    ('Bengaluru', 'Karnataka',   'Asia/Kolkata'),
    ('Mumbai',    'Maharashtra', 'Asia/Kolkata'),
    ('Delhi',     'Delhi',       'Asia/Kolkata');

INSERT INTO theaters (city_id, refund_policy_id, name, address) VALUES
    (1, 1, 'PVR Forum Mall',   'Hosur Road, Koramangala'),
    (1, 2, 'INOX Garuda Mall', 'Magrath Road, Ashok Nagar'),
    (2, 1, 'PVR Phoenix',      'Lower Parel'),
    (3, 3, 'PVR Select City',  'Saket District Centre');

INSERT INTO screens (theater_id, name) VALUES
    (1, 'Screen 1'), (1, 'Screen 2'),
    (2, 'Audi 1'),
    (3, 'Screen 1'),
    (4, 'Screen 1');

-- Seats: rows A-B regular, C-D premium, E recliner. Ten seats per row, so every
-- screen has 50 seats.
INSERT INTO seats (screen_id, row_label, seat_number, tier)
SELECT s.id,
       r.row_label,
       n.seat_number,
       r.tier
  FROM screens s
 CROSS JOIN (VALUES ('A', 'REGULAR'),
                    ('B', 'REGULAR'),
                    ('C', 'PREMIUM'),
                    ('D', 'PREMIUM'),
                    ('E', 'RECLINER')) AS r(row_label, tier)
 CROSS JOIN generate_series(1, 10) AS n(seat_number);

INSERT INTO movies (title, duration_minutes, language, certification, synopsis) VALUES
    ('Dune: Part Three',    166, 'English', 'UA', 'The Fremen war reaches its conclusion.'),
    ('Laapataa Ladies 2',   124, 'Hindi',   'U',  'Two brides, one train, and a case of mistaken identity.'),
    ('Kantara: Chapter 2',  148, 'Kannada', 'UA', 'The forest remembers what the village forgot.'),
    ('The Silent Protocol', 112, 'English', 'A',  'A cryptographer uncovers a conspiracy in plain sight.');

-- ---------------------------------------------------------------------- shows --
--
-- Seven days from today across four slots. Slots are spaced so no two shows overlap
-- on a screen, which the ex_show_no_overlap constraint would otherwise reject.
-- Spanning a week means every refund tier is demonstrable: a show 6 days out refunds
-- 100%, one later today refunds nothing.

INSERT INTO shows (movie_id, screen_id, starts_at, ends_at, base_price, status)
SELECT m.movie_id,
       m.screen_id,
       slot.starts_at,
       slot.starts_at + (mv.duration_minutes || ' minutes')::INTERVAL,
       m.base_price,
       'SCHEDULED'
  FROM (VALUES (1, 1, 250.00),
               (2, 2, 220.00),
               (3, 3, 200.00),
               (1, 4, 300.00),
               (4, 5, 260.00)) AS m(movie_id, screen_id, base_price)
  JOIN movies mv ON mv.id = m.movie_id
 CROSS JOIN LATERAL (
       SELECT (CURRENT_DATE + d)::TIMESTAMPTZ + h AS starts_at
         FROM generate_series(0, 6) AS d
        CROSS JOIN (VALUES (INTERVAL '10 hours'),
                           (INTERVAL '14 hours'),
                           (INTERVAL '18 hours'),
                           (INTERVAL '21 hours')) AS t(h)
 ) AS slot;

-- ----------------------------------------------------------------- show seats --
--
-- Materializes one row per seat per show. This is the unit of allocation, and the
-- reason a specific seat can be locked with SELECT ... FOR UPDATE.
--
-- Price resolves the full rule set at creation time:
--     base x tier multiplier x weekend surcharge

INSERT INTO show_seats (show_id, seat_id, status, price)
SELECT sh.id,
       st.id,
       'AVAILABLE',
       ROUND(
           sh.base_price
           * pt.multiplier
           * CASE
                 WHEN EXTRACT(ISODOW FROM sh.starts_at AT TIME ZONE 'Asia/Kolkata') IN (6, 7)
                 THEN pc.weekend_multiplier
                 ELSE 1.0
             END,
           2)
  FROM shows sh
  JOIN seats st ON st.screen_id = sh.screen_id
  JOIN pricing_tiers pt ON pt.tier = st.tier
 CROSS JOIN pricing_config pc
 WHERE pc.id = 1;

-- ----------------------------------------------------------------- discounts --
--
-- Includes deliberately unusable codes so the failure paths are demonstrable
-- without editing data.

INSERT INTO discount_codes (code, type, value, max_discount, min_order_amount,
                            valid_from, valid_to, usage_limit, times_used,
                            per_user_limit, active)
VALUES
    -- 50% off, capped at 150, on orders of 300+
    ('FIRST50',  'PERCENT', 50.00,  150.00, 300.00,
     now() - INTERVAL '30 days', now() + INTERVAL '90 days', 1000, 0, 1, TRUE),
    -- Flat 100 off orders of 500+
    ('FLAT100',  'FLAT',    100.00, NULL,   500.00,
     now() - INTERVAL '30 days', now() + INTERVAL '90 days', 500,  0, 5, TRUE),
    -- Unrestricted small discount
    ('WELCOME10','PERCENT', 10.00,  100.00, 0.00,
     now() - INTERVAL '7 days',  now() + INTERVAL '365 days', NULL, 0, NULL, TRUE),
    -- Window has closed: exercises the expiry path
    ('EXPIRED20','PERCENT', 20.00,  NULL,   0.00,
     now() - INTERVAL '60 days', now() - INTERVAL '1 day',  NULL, 0, NULL, TRUE),
    -- Usage limit already reached: exercises the exhausted path
    ('SOLDOUT',  'FLAT',    50.00,  NULL,   0.00,
     now() - INTERVAL '30 days', now() + INTERVAL '30 days', 10, 10, NULL, TRUE),
    -- Manually disabled
    ('DISABLED', 'FLAT',    75.00,  NULL,   0.00,
     now() - INTERVAL '30 days', now() + INTERVAL '30 days', NULL, 0, NULL, FALSE);
