#!/usr/bin/env bash
# Run only inside a disposable container, with no Docker socket or production volumes.
set -Eeuo pipefail
test ! -e /opt/camp-agent
mkdir -p /opt/camp-agent/releases/fixture /opt/camp-agent/backups /tmp/mock-bin /tmp/documents
ln -s /opt/camp-agent/releases/fixture /opt/camp-agent/current
printf 'original fixture\n' > /tmp/documents/document.txt
cat > /tmp/mock-bin/docker <<'MOCK'
#!/usr/bin/env bash
set -eu
test "${FAIL_BACKUP:-0}" = 0 || exit 42
test "$1" = exec
case "$2" in
    camp-agent-mysql-1) printf '%s\n' 'CREATE TABLE fixture(id INT); INSERT INTO fixture VALUES(1);' ;;
    camp-agent-backend-1) tar -C /tmp/documents -czf - . ;;
    *) exit 99 ;;
esac
MOCK
chmod 700 /tmp/mock-bin/docker
export PATH="/tmp/mock-bin:$PATH"
old=/opt/camp-agent/backups/backup-20261001T000000Z-old
failed=/opt/camp-agent/backups/backup-20261001T000000Z-failed
manual=/opt/camp-agent/backups/manual-history
mkdir "$old" "$failed" "$manual"
touch "$old/SUCCESS"
touch -d '9 days ago' "$old" "$failed" "$manual"
bash /scripts/backup-server.sh
test ! -e "$old"
test -d "$failed"
test -d "$manual"
completed=$(find /opt/camp-agent/backups -type f -name SUCCESS -printf '%h\n')
test -n "$completed"
test "$(stat -c %a "$completed")" = 700
test "$(stat -c %a "$completed/mysql.sql.gz")" = 600
(cd "$completed"; sha256sum -c SHA256SUMS)
tar -xOzf "$completed/documents.tar.gz" ./document.txt | cmp - /tmp/documents/document.txt
if FAIL_BACKUP=1 bash /scripts/backup-server.sh; then exit 1; fi
test "$(find /opt/camp-agent/backups -name SUCCESS | wc -l)" = 1
grep -l '^FAILED ' /opt/camp-agent/backups/backup-*/status.txt > /dev/null
printf 'Backup integrity, permissions, retention and failure handling passed.\n'
