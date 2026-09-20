-- Per-booking message thread between learner and provider.
create table booking_messages (
    id         bigserial primary key,
    booking_id bigint       not null references bookings (id),
    sender_id  bigint       not null references users (id),
    body       varchar(1000) not null,
    created_at timestamptz  not null default now()
);
create index idx_messages_booking on booking_messages (booking_id, id);
