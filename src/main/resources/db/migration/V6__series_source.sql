alter table book add column series_source varchar(20);
-- everything recognised before is re-checked once with the national library first
update book set series_checked = false;
