-- Series were removed as a feature (Tom, 25.11.2026). Backup before this migration:
-- /mnt/storagebox/shelved/pre-v10/shelved-20261125-223839.dump (pg_restore to roll back).
drop table series_catalog;
alter table book
    drop column series_name,
    drop column series_volume,
    drop column series_ref,
    drop column series_source,
    drop column series_checked;
alter table wish
    drop column series_name,
    drop column series_volume;
