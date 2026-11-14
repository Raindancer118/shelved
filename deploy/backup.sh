#!/bin/sh
# Daily backup of shelved to the Storage Box: Postgres dump + cover images. Keeps the newest copy only
# (Tom's rule for umaroth); an old copy is removed only after the new one exists and is not empty.
set -eu
DEST=${BACKUP_DIR:-/mnt/storagebox/shelved}
KEEP=${BACKUP_KEEP:-1}
STAMP=$(date +%Y%m%d-%H%M%S)
mountpoint -q /mnt/storagebox || { echo "storage box not mounted" >&2; exit 1; }
mkdir -p "$DEST"

docker exec shelved-db pg_dump -U shelved -d shelved -Fc > "$DEST/shelved-$STAMP.dump.part" </dev/null
test -s "$DEST/shelved-$STAMP.dump.part"
mv "$DEST/shelved-$STAMP.dump.part" "$DEST/shelved-$STAMP.dump"

docker exec shelved-app tar czf - -C /data . > "$DEST/covers-$STAMP.tar.gz.part" </dev/null
test -s "$DEST/covers-$STAMP.tar.gz.part"
mv "$DEST/covers-$STAMP.tar.gz.part" "$DEST/covers-$STAMP.tar.gz"

ls -1t "$DEST"/shelved-*.dump | tail -n +$((KEEP + 1)) | xargs -r rm -f
ls -1t "$DEST"/covers-*.tar.gz | tail -n +$((KEEP + 1)) | xargs -r rm -f
echo "backup ok: $DEST/shelved-$STAMP.dump, covers-$STAMP.tar.gz"
