#!/bin/sh
# 잠금 경합 재현(postgres 컨테이너 안에서): 세션 A가 BEGIN 후 행을 잠그고 커밋하지 않은 채 HOLD초 머문다(idle in transaction),
# WAIT초 뒤 세션 B가 같은 행을 바꾸려다 기다린다(Lock). 둘 다 ROLLBACK으로 끝나 데이터는 그대로다.
#   사용: sh hold.sh "<A의 잠그는 문장>" "<B의 문장>" [HOLD=40] [WAIT=3]
set -eu
A_SQL=$1; B_SQL=$2; HOLD=${3:-40}; WAIT=${4:-3}
( printf 'BEGIN;\n%s;\n' "$A_SQL"; sleep "$HOLD"; printf 'ROLLBACK;\n' ) |
  PGAPPNAME=lock-holder-A psql -U dance -d dance_db -qAt >/tmp/a.log 2>&1 &
sleep "$WAIT"
( printf 'BEGIN;\n%s;\nROLLBACK;\n' "$B_SQL" ) |
  PGAPPNAME=lock-waiter-B psql -U dance -d dance_db -qAt >/tmp/b.log 2>&1 &
wait
echo "A: $(cat /tmp/a.log | tr '\n' ' ')"; echo "B: $(cat /tmp/b.log | tr '\n' ' ')"
