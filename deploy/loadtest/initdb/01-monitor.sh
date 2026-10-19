#!/bin/sh
# 측정 전용 모니터링 계정(sql_exporter). pg_monitor = pg_stat_activity의 다른 사용자 쿼리 문장까지 읽기.
# 비밀번호는 .env의 MONITOR_PASSWORD에서 받는다(명령줄에 쓰지 않고 psql 변수로 넘김).
set -eu
psql -v ON_ERROR_STOP=1 -v pw="$MONITOR_PASSWORD" -U "$POSTGRES_USER" -d postgres <<'SQL'
CREATE ROLE monitor LOGIN PASSWORD :'pw' CONNECTION LIMIT 2;
GRANT pg_monitor TO monitor;
SQL
