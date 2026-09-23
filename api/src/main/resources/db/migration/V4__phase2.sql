-- Idempotency keys for POST /api/bookings (safe retries / double-click protection)
create table idempotency_keys (
    user_id    bigint       not null references users (id),
    idem_key   varchar(80)  not null,
    booking_id bigint       not null references bookings (id),
    created_at timestamptz  not null default now(),
    primary key (user_id, idem_key)
);

-- Extra guard against a race creating two open bookings for the same listing + learner,
-- on top of the row-level lock taken in BookingService.request().
create unique index uq_open_booking on bookings (listing_id, learner_id)
    where status in ('REQUESTED', 'ACCEPTED');
