#!/usr/bin/env bash
# /etc/dance-api.env의 KEY 한 줄을 표준 입력으로 받은 값으로 바꾼다(없으면 더한다). 값은 화면·명령줄·셸 기록에 남지 않는다.
#   다른 서버에서 값을 꺼내 파이프로 넘긴다:
#   ssh odo-redis 'sudo sed -n "s/^requirepass //p" /etc/redis/odo.conf' | ssh odo 'sudo bash /tmp/set-env-key.sh REDIS_PASSWORD'
#   값이 비밀이 아니면: echo 1 | ssh odo-app2 'sudo bash /tmp/set-env-key.sh CHAT_SERVER_INDEX'
set -euo pipefail
KEY="$1"
FILE="${ENV_FILE:-/etc/dance-api.env}"
[[ "$KEY" =~ ^[A-Z0-9_]+$ ]] || { echo "키 이름이 이상합니다: $KEY" >&2; exit 1; }
IFS= read -r VALUE || true
[ -n "$VALUE" ] || { echo "$KEY: 받은 값이 비었습니다 — 바꾸지 않음" >&2; exit 1; }
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
grep -v "^${KEY}=" "$FILE" > "$TMP" || true
printf '%s=%s\n' "$KEY" "$VALUE" >> "$TMP"
install -m 600 -o ubuntu -g ubuntu "$TMP" "$FILE"
echo "$KEY 설정함 ($FILE)"
