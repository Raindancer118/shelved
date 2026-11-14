-- Own accounts and libraries with members. From here on the owner_id columns of book, shelf and scan
-- hold the id of the LIBRARY the row belongs to (no longer the login subject).

create table library (
    id          uuid primary key,
    name        varchar(120) not null,
    kind        varchar(20)  not null,
    slug        varchar(80)  not null unique,
    created_at  timestamptz  not null
);

create table account (
    id              uuid primary key,
    email           varchar(254) unique,
    display_name    varchar(120),
    password_hash   varchar(100),
    email_verified  boolean      not null default false,
    oidc_subject    varchar(200) unique,
    created_at      timestamptz  not null
);

create table membership (
    id          uuid primary key,
    library_id  uuid not null references library (id) on delete cascade,
    account_id  uuid not null references account (id) on delete cascade,
    role        varchar(20) not null,
    created_at  timestamptz not null,
    unique (library_id, account_id)
);
create index membership_account on membership (account_id);

create table account_token (
    id          uuid primary key,
    account_id  uuid not null references account (id) on delete cascade,
    kind        varchar(20)  not null,
    token_hash  varchar(64)  not null unique,
    payload     varchar(254),
    expires_at  timestamptz  not null,
    used_at     timestamptz
);

create table invitation (
    id           uuid primary key,
    library_id   uuid not null references library (id) on delete cascade,
    email        varchar(254) not null,
    role         varchar(20)  not null,
    token_hash   varchar(64)  not null unique,
    invited_by   uuid references account (id) on delete set null,
    expires_at   timestamptz  not null,
    accepted_at  timestamptz
);

-- Every login that already has data becomes an account (linked to its SSO subject) with its own library.
create temporary table owner_map as
select o.owner_id, gen_random_uuid() as account_id, gen_random_uuid() as library_id
from (select owner_id from book union select owner_id from shelf union select owner_id from scan) o;

insert into account (id, oidc_subject, email_verified, created_at)
select account_id, owner_id, true, now() from owner_map;

insert into library (id, name, kind, slug, created_at)
select library_id, 'Meine Bibliothek', 'PRIVATE', 'b-' || substr(replace(library_id::text, '-', ''), 1, 12), now() from owner_map;

insert into membership (id, library_id, account_id, role, created_at)
select gen_random_uuid(), library_id, account_id, 'OWNER', now() from owner_map;

update book b set owner_id = m.library_id::text from owner_map m where b.owner_id = m.owner_id;
update shelf s set owner_id = m.library_id::text from owner_map m where s.owner_id = m.owner_id;
update scan s set owner_id = m.library_id::text from owner_map m where s.owner_id = m.owner_id;

drop table owner_map;
