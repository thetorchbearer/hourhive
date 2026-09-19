create table users (
    id            bigserial primary key,
    email         varchar(254) not null unique,
    display_name  varchar(60)  not null,
    password_hash varchar(100) not null,
    bio           varchar(500),
    created_at    timestamptz  not null default now()
);

create table listings (
    id          bigserial primary key,
    owner_id    bigint        not null references users (id),
    category    varchar(40)   not null,
    title       varchar(100)  not null,
    description varchar(1000) not null,
    minutes     integer       not null check (minutes between 15 and 240),
    active      boolean       not null default true,
    created_at  timestamptz   not null default now()
);
create index idx_listings_active_category on listings (active, category);
create index idx_listings_owner on listings (owner_id);

create table bookings (
    id          bigserial primary key,
    listing_id  bigint      not null references listings (id),
    learner_id  bigint      not null references users (id),
    provider_id bigint      not null references users (id),
    minutes     integer     not null,
    status      varchar(20) not null,
    note        varchar(500),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    check (learner_id <> provider_id)
);
create index idx_bookings_learner on bookings (learner_id);
create index idx_bookings_provider on bookings (provider_id);

-- Double-entry style time ledger. A user's balance is the sum of their entries.
create table ledger_entries (
    id            bigserial primary key,
    user_id       bigint      not null references users (id),
    delta_minutes integer     not null,
    kind          varchar(20) not null,
    booking_id    bigint references bookings (id),
    note          varchar(200),
    created_at    timestamptz not null default now()
);
create index idx_ledger_user on ledger_entries (user_id);

create table reviews (
    id          bigserial primary key,
    booking_id  bigint      not null unique references bookings (id),
    reviewer_id bigint      not null references users (id),
    reviewee_id bigint      not null references users (id),
    rating      integer     not null check (rating between 1 and 5),
    comment     varchar(500),
    created_at  timestamptz not null default now()
);
create index idx_reviews_reviewee on reviews (reviewee_id);
