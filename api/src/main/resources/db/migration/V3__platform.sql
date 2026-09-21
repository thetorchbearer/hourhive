-- Roles, moderation flags
alter table users add column role     varchar(20) not null default 'USER';
alter table users add column disabled boolean     not null default false;

-- Scheduling
alter table bookings add column scheduled_at     timestamptz;
alter table bookings add column reschedule_count integer not null default 0;
create index idx_bookings_sched_provider on bookings (provider_id, scheduled_at) where scheduled_at is not null;
create index idx_bookings_sched_learner  on bookings (learner_id, scheduled_at)  where scheduled_at is not null;

-- Weekly recurring availability, minutes since midnight UTC
create table availability_slots (
    id           bigserial primary key,
    user_id      bigint   not null references users (id),
    day_of_week  smallint not null check (day_of_week between 1 and 7),
    start_minute integer  not null check (start_minute between 0 and 1439),
    end_minute   integer  not null check (end_minute between 1 and 1440),
    check (end_minute > start_minute)
);
create index idx_slots_user on availability_slots (user_id);

-- Skill requests board
create table skill_requests (
    id           bigserial primary key,
    requester_id bigint        not null references users (id),
    category     varchar(40)   not null,
    title        varchar(100)  not null,
    description  varchar(1000) not null,
    minutes      integer       not null check (minutes between 15 and 240),
    status       varchar(20)   not null default 'OPEN',
    created_at   timestamptz   not null default now()
);
create index idx_requests_status on skill_requests (status, created_at desc);
create index idx_requests_requester on skill_requests (requester_id);

-- In-app notifications
create table notifications (
    id         bigserial primary key,
    user_id    bigint       not null references users (id),
    kind       varchar(30)  not null,
    message    varchar(300) not null,
    link       varchar(100),
    is_read    boolean      not null default false,
    created_at timestamptz  not null default now()
);
create index idx_notifications_user on notifications (user_id, id desc);

-- Reports and moderation
create table reports (
    id              bigserial primary key,
    reporter_id     bigint       not null references users (id),
    target_type     varchar(10)  not null check (target_type in ('USER', 'LISTING')),
    target_id       bigint       not null,
    reason          varchar(60)  not null,
    details         varchar(500),
    status          varchar(20)  not null default 'OPEN',
    resolved_by     bigint references users (id),
    resolution_note varchar(500),
    created_at      timestamptz  not null default now(),
    resolved_at     timestamptz
);
create index idx_reports_status on reports (status, id desc);

-- Audit log
create table audit_log (
    id          bigserial primary key,
    actor_id    bigint,
    action      varchar(40) not null,
    entity_type varchar(30),
    entity_id   bigint,
    detail      varchar(500),
    request_id  varchar(64),
    created_at  timestamptz not null default now()
);
create index idx_audit_created on audit_log (id desc);
