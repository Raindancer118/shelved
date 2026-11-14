create table shelf (
    id          uuid primary key,
    owner_id    varchar(200) not null,
    name        varchar(100) not null,
    position    int          not null,
    created_at  timestamptz  not null
);
create index shelf_owner on shelf (owner_id);

create table shelf_board (
    shelf_id    uuid not null references shelf (id) on delete cascade,
    board_index int  not null,
    width_mm    int  not null,
    primary key (shelf_id, board_index)
);

create table book (
    id               uuid primary key,
    owner_id         varchar(200) not null,
    isbn13           varchar(13),
    title            varchar(500) not null,
    title_sort       varchar(500) not null,
    subtitle         varchar(1000),
    authors          varchar(1000),
    author_sort      varchar(300),
    publisher        varchar(300),
    published_year   int,
    page_count       int,
    height_mm        int,
    thickness_mm     int,
    format           varchar(20),
    language         varchar(10),
    description      text,
    series_name      varchar(300),
    series_volume    int,
    list_price_cents int,
    cover_file       varchar(200),
    metadata_source  varchar(20),
    shelf_id         uuid references shelf (id) on delete set null,
    board_number     int,
    board_position   int,
    series_checked   boolean      not null default false,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null
);
create index book_owner on book (owner_id);
create index book_owner_isbn on book (owner_id, isbn13);

create table scan (
    id           uuid primary key,
    owner_id     varchar(200) not null,
    mode         varchar(20)  not null,
    status       varchar(20)  not null,
    shelf_id     uuid,
    board_number int,
    error        varchar(1000),
    result_json  text,
    created_at   timestamptz  not null,
    finished_at  timestamptz
);
create index scan_owner on scan (owner_id, created_at);

create table series_catalog (
    series_key   varchar(400) primary key,
    series_name  varchar(300) not null,
    author       varchar(300),
    volumes_json text         not null,
    status       varchar(20)  not null,
    error        varchar(1000),
    fetched_at   timestamptz  not null
);
