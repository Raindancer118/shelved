-- Books a library wants. owner_id is the library id, like book/shelf/scan.
create table wish (
    id               uuid primary key,
    owner_id         varchar(200)  not null,
    isbn13           varchar(13),
    title            varchar(500)  not null,
    subtitle         varchar(1000),
    authors          varchar(1000),
    publisher        varchar(300),
    published_year   int,
    series_name      varchar(300),
    series_volume    int,
    list_price_cents int,
    cover_file       varchar(200),
    -- true once the user decided to keep the wish although the book is in the library (gift, second copy)
    keep_when_owned  boolean       not null default false,
    created_at       timestamptz   not null
);
create index wish_owner on wish (owner_id);
