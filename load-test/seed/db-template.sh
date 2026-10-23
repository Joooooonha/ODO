#!/usr/bin/env bash
# 측정 DB 출발점 만들기·되돌리기.
#
#   PSQL="docker compose -f deploy/loadtest/compose.yml exec -T postgres psql -U dance" \
#     load-test/seed/db-template.sh save     # 시드를 넣은 dance_db → dance_seed(템플릿)로 남긴다
#     load-test/seed/db-template.sh reset    # 실행 전마다: dance_db를 지우고 dance_seed에서 다시 만든다
#
# 두 명령 모두 dance_db에 붙은 연결이 없어야 한다 → 앱(app1·app2)을 먼저 멈춘다. reset은 남은 연결을 끊는다(FORCE).
# 덤프·복원보다 빠르다(파일 복사). 실행마다 같은 출발점 + JVM도 새로 시작한다.
set -euo pipefail
PSQL=${PSQL:?PSQL 명령을 지정하세요 (예: docker compose ... exec -T postgres psql -U dance)}
run() { $PSQL -d postgres -v ON_ERROR_STOP=1 -qAt -c "$1"; }
case "${1:-}" in
  save)
    run "DROP DATABASE IF EXISTS dance_seed"
    run "CREATE DATABASE dance_seed TEMPLATE dance_db"
    echo "dance_seed 저장: $(run "SELECT pg_size_pretty(pg_database_size('dance_seed'))")"
    ;;
  reset)
    run "SELECT 1 FROM pg_database WHERE datname = 'dance_seed'" | grep -q 1 || { echo "dance_seed가 없습니다(save 먼저)"; exit 1; }
    run "DROP DATABASE IF EXISTS dance_db WITH (FORCE)"
    run "CREATE DATABASE dance_db TEMPLATE dance_seed"
    echo "dance_db를 dance_seed에서 다시 만들었습니다"
    ;;
  *)
    echo "사용: $0 save|reset"; exit 2 ;;
esac
