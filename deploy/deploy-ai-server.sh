#!/usr/bin/env bash
#
# 분석 서버(FastAPI)를 맥미니로 배포한다. 로컬(맥)에서 실행.
#
#   ./deploy/deploy-ai-server.sh
#
# 맥미니는 Fedora Asahi Remix (Apple Silicon 위 Linux, aarch64)다.
# **git clone을 쓰지 않는다** — 원격 저장소가 아직 예전 커밋이라 작업 내용이 없다.
# 코드가 커밋되면 clone 방식으로 바꿔도 된다.
#
# 사전 조건 (한 번만, 맥미니에서 sudo 필요):
#   sudo dnf install -y python3.10 ffmpeg-free
#
# ffmpeg-free가 왜 필수인가: 회전 메타데이터를 ffprobe로 읽는다. 없으면
# 세로로 촬영한 영상이 **옆으로 누운 채** 분석된다. 실측 피해:
#   관절 유효율 0.909 → 0.809, 평균 오차 25도 → 31도, 채점률 85% → 65%

set -euo pipefail

HOST="${AI_HOST:-macmini}"
# 원격 홈 기준 상대 경로. 사용자명이 기기마다 달라 절대경로를 박지 않는다.
DEST="${AI_DEST:-dance-ai}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> 대상: $HOST:$DEST"

# 파이썬 확인 — torch 휠이 cp310~cp313이라 3.14로는 설치되지 않는다.
ssh "$HOST" 'command -v python3.10 >/dev/null' || {
  echo "❌ python3.10이 없습니다. 먼저 실행하세요:"
  echo "   ssh $HOST 'sudo dnf install -y python3.10 ffmpeg-free'"
  exit 1
}

# ffprobe는 회전 메타데이터를 읽는 데 쓴다. 없으면 세로 영상이 누운 채 분석되고,
# 코드는 경고만 남기고 계속 돌기 때문에 여기서 배포를 막는다.
ssh "$HOST" 'command -v ffprobe >/dev/null' || {
  echo "❌ ffprobe가 없습니다. 세로 촬영 영상이 옆으로 누운 채 분석됩니다."
  echo "   ssh $HOST 'sudo dnf install -y ffmpeg-free'"
  exit 1
}

ssh "$HOST" "mkdir -p '$DEST'"

echo "==> 코드 동기화"
# venv·모델 가중치·산출물은 제외한다. 특히 venv는 맥용 바이너리라 옮기면 깨진다.
# testdata/도 제외한다 — 원격에만 있는 디렉터리라 --delete가 지워버린다.
rsync -az --delete \
  --exclude 'venv/' --exclude '__pycache__/' --exclude '*.pyc' \
  --exclude 'output/' --exclude 'uploads/' --exclude 'storage-data/' \
  --exclude '*.pt' --exclude '*.task' --exclude 'models/' \
  --exclude 'testdata/' \
  "$ROOT/ai-server/" "$HOST:$DEST/"

echo "==> 가상환경 · 의존성"
ssh "$HOST" "cd '$DEST'
  [ -d venv ] || python3.10 -m venv venv
  ./venv/bin/python -m pip install -q --upgrade pip
  ./venv/bin/python -m pip install -q -r requirements.txt
"

echo "==> 설치 확인"
ssh "$HOST" "cd '$DEST' && ./venv/bin/python -c \"
import ultralytics, torch, cv2, numpy, dtaidistance
print('  ultralytics', ultralytics.__version__)
print('  torch      ', torch.__version__)
print('  opencv     ', cv2.__version__)
print('  numpy      ', numpy.__version__)
print('  장치       ', 'cuda' if torch.cuda.is_available() else 'cpu')
\" 2>&1 | grep -v Warning"

echo
echo "==> 완료. 서버 실행:"
echo "   ssh $HOST 'cd ~/$DEST && ./venv/bin/python -m uvicorn main:app --host \$(tailscale ip -4) --port 8000'"
