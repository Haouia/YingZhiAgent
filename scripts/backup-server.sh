#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
base=/opt/camp-agent
test "$(readlink -f "$base")" = "$base"
install -d -m 700 "$base/backups"
test "$(readlink -f "$base/backups")" = "$base/backups"
exec 9>"$base/backups/.backup.lock"
flock -n 9 || exit 1
stamp=$(date -u +%Y%m%dT%H%M%SZ)
run=$(mktemp -d "$base/backups/backup-$stamp-XXXXXX")
trap 'printf "FAILED %s\n" "$(date -u +%FT%TZ)" > "$run/status.txt"; exit 1' ERR
printf 'IN_PROGRESS\n' > "$run/status.txt"
readlink -f "$base/current" > "$run/release.txt"
# shortcut: database and files are sequential snapshots; quiesce writes when strict point-in-time consistency is required.
docker exec camp-agent-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u"$MYSQL_USER" --single-transaction --no-tablespaces "$MYSQL_DATABASE"' | gzip > "$run/mysql.sql.gz"
docker exec camp-agent-backend-1 tar -C /data/documents -czf - . > "$run/documents.tar.gz"
gzip -t "$run/mysql.sql.gz" "$run/documents.tar.gz"
test -s "$run/mysql.sql.gz"
tar -tzf "$run/documents.tar.gz" > /dev/null
(cd "$run"; sha256sum mysql.sql.gz documents.tar.gz > SHA256SUMS)
printf 'SUCCESS %s\n' "$(date -u +%FT%TZ)" > "$run/status.txt"
touch "$run/SUCCESS"
# Only prune successful backups created by this script under the verified directory.
while IFS= read -r -d '' old; do
    test ! -L "$old"
    test "$(dirname "$(readlink -f "$old")")" = "$base/backups"
    if test -f "$old/SUCCESS"; then rm -rf -- "$old"; fi
done < <(find "$base/backups" -mindepth 1 -maxdepth 1 -type d -name 'backup-20*T*Z-*' -mtime +6 -print0)
printf 'Backup verified: %s\n' "$run"
