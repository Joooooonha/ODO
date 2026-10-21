#!/usr/bin/env bash
# 앱 서버 여러 대에 한꺼번에 배포한다(10/21 분산 구성, deploy.yml이 GitHub 러너에서 부른다).
#
#   deploy-hosts.sh <첫 서버> [나머지 서버 ...]
#
# 서비스를 잠깐 멈추는 방식이다(준하 결정: 멈춰도 되니 안전하고 빠르게). 옛 버전과 새 버전이 함께 돌지 않는다.
#   1. 모든 서버: 지금 jar(첫 서버는 화면도)를 .bak로 남기고 멈춘다
#   2. 모든 서버: 새 jar로 바꾼다. 첫 서버만 화면(dist)을 바꾼다(앞단 nginx가 첫 서버에 있다)
#   3. 첫 서버를 먼저 띄워 기동을 확인한다 — Flyway 마이그레이션은 여기서 한 번 적용된다
#   4. 나머지 서버를 띄워 기동을 확인한다
#   하나라도 실패하면 모든 서버를 멈추고 .bak로 되돌려 다시 띄운다(첫 서버부터)
#
# 미리 올려 둔 것: 각 서버 /tmp/deploy-app.jar, 첫 서버 /tmp/deploy-dist/
# 기동 확인 주소: 서버의 /etc/dance-api.env에 SERVER_ADDRESS가 있으면 그 주소(앱-2는 사설 IP에 바인딩), 없으면 127.0.0.1
set -euo pipefail

SSH_OPTS=(-i "$HOME/.ssh/deploy_key" -o IdentitiesOnly=yes -o ConnectTimeout=15 -o BatchMode=yes)
FIRST="$1"
ALL=("$@")

remote() { # host script
  ssh "${SSH_OPTS[@]}" "ubuntu@$1" 'bash -se' <<<"$2"
}

# 기동 확인(실측 14초, 마이그레이션이 있으면 더 걸린다 → 최대 2분)
HEALTH='
ADDR="$(sudo sed -n "s/^SERVER_ADDRESS=//p" /etc/dance-api.env | tail -1)"; ADDR="${ADDR:-127.0.0.1}"
for i in $(seq 1 40); do
  if curl -sf -o /dev/null "http://$ADDR:8080/api/push/public-key"; then echo "✅ $(hostname) 기동 확인 (${i}회차, $ADDR)"; exit 0; fi
  sleep 3
done
echo "❌ $(hostname) 기동하지 못했습니다"; sudo journalctl -u dance-api -n 40 --no-pager | tail -25; exit 1
'

rollback() {
  echo "↩️  모든 서버를 직전 버전으로 되돌립니다"
  for h in "${ALL[@]}"; do
    remote "$h" 'sudo systemctl stop dance-api || true; sudo cp /opt/dance-api/app.jar.bak /opt/dance-api/app.jar' || echo "⚠️  $h 되돌리기 실패 — 직접 확인"
  done
  remote "$FIRST" 'if [ -d /var/www/odostudio/dist.bak ]; then sudo rsync -a --delete /var/www/odostudio/dist.bak/ /var/www/odostudio/dist/; fi' || true
  for h in "${ALL[@]}"; do
    remote "$h" 'sudo systemctl start dance-api' || true
    remote "$h" "$HEALTH" || echo "⚠️  $h 직전 버전도 뜨지 않았습니다 — 직접 확인"
  done
  exit 1
}

# 1단계에서 실패하면 아직 아무것도 바꾸지 않았다 → 멈춘 서버만 그대로 다시 띄운다(.bak는 쓰지 않는다)
STOPPED=()
abort_before_swap() {
  echo "↩️  교체 전에 실패 — 멈춘 서버를 그대로 다시 띄웁니다: ${STOPPED[*]:-없음}"
  for h in "${STOPPED[@]}"; do remote "$h" 'sudo systemctl start dance-api' || echo "⚠️  $h 다시 띄우기 실패 — 직접 확인"; done
  exit 1
}

echo "== 1. 모든 서버 멈춤(직전 버전 보관): ${ALL[*]}"
for h in "${ALL[@]}"; do
  remote "$h" 'set -e; test -s /tmp/deploy-app.jar; sudo cp /opt/dance-api/app.jar /opt/dance-api/app.jar.bak; sudo systemctl stop dance-api; echo "⏹  $(hostname) 멈춤"' \
    || { STOPPED+=("$h"); abort_before_swap; }
  STOPPED+=("$h")
done
remote "$FIRST" 'set -e; if [ -d /var/www/odostudio/dist ]; then sudo rsync -a --delete /var/www/odostudio/dist/ /var/www/odostudio/dist.bak/; fi' || abort_before_swap

echo "== 2. 교체"
for h in "${ALL[@]}"; do
  remote "$h" 'set -e; sudo mv /tmp/deploy-app.jar /opt/dance-api/app.jar' || rollback
done
remote "$FIRST" 'set -e; sudo rsync -a --delete /tmp/deploy-dist/ /var/www/odostudio/dist/; rm -rf /tmp/deploy-dist' || rollback

echo "== 3. 첫 서버 기동(마이그레이션): $FIRST"
remote "$FIRST" 'sudo systemctl start dance-api' || rollback
remote "$FIRST" "$HEALTH" || rollback

echo "== 4. 나머지 서버 기동"
for h in "${ALL[@]:1}"; do
  remote "$h" 'sudo systemctl start dance-api' || rollback
  remote "$h" "$HEALTH" || rollback
done
echo "✅ 모든 서버 배포 끝: ${ALL[*]}"
