-- shelf photos are kept (scaled down) as proof of ownership for the insurance inventory
alter table scan add column photo_file varchar(200);
