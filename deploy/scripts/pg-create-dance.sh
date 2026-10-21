#!/usr/bin/env bash
# DB 서버에서: 역할 dance(비밀번호는 표준 입력)와 빈 dance_db를 만든다. 이미 있으면 비밀번호만 맞춘다.
#   ssh odo 'sudo sed -n "s/^SPRING_DATASOURCE_PASSWORD=//p" /etc/dance-api.env' | ssh odo-db 'sudo bash /tmp/pg-create-dance.sh'
set -euo pipefail
IFS= read -r PW || true
[ -n "$PW" ] || { echo "비밀번호를 받지 못했습니다" >&2; exit 1; }
cd /tmp
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -v pw="$PW" <<'SQL'
SELECT format('CREATE ROLE dance LOGIN PASSWORD %L', :'pw') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dance') \gexec
SELECT format('ALTER ROLE dance PASSWORD %L', :'pw') \gexec
SELECT 'CREATE DATABASE dance_db OWNER dance' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'dance_db') \gexec
SQL
echo "역할 dance, 데이터베이스 dance_db 준비됨"
