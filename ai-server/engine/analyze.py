"""검증된 엔진으로 두 영상을 비교한다. `ai-server`의 새 분석 진입점.

초기 프로토타입의 `analyzer.DanceAnalyzer`(삭제됨)를 대체한다. 방법론 근거는
`docs/analysis-engine-report.md` 참조.

기존 구현 대비 바뀐 것:

| 항목 | 기존 | 여기 |
|---|---|---|
| 결측 관절 | `similarity = 1.0` (완벽 일치로 계산) | 명시적 마스크, 유효 항목만 평균 |
| 비교 표현 | 좌표 벡터 코사인 유사도 | 관절 각도 14종 (몸통 상대) |
| 백엔드 | MediaPipe (linux aarch64 휠 없음) | YOLO-pose (연구에서 측정에 쓴 것과 동일) |
| 분석 관절 | 33개 중 22개 (손가락 포함) | COCO17에서 유도한 각도 |
| 정렬 | fastdtw, 밴드 없음 | `dtw_ndim` + 밴드 + 각속도 보조 |
| 회전 | 미처리 | 자동 보정 |
| 결과 | 0~100 점수 | 틀린 동작 개수 + 구간 피드백 |
"""
import os

from engine.adapter import extract_sequence_isolated
from engine.dtw_compare import compare_sequences
from engine.feedback import summarize
from engine.features import build_features

# 밴드 폭(초). 구간이 지정되면 전역 오프셋이 제거되므로 좁게 잡는다.
# 앵커 잔차 표준편차 0.13~0.20초의 3σ 수준 (TASKS.md 시도 7).
DEFAULT_BAND_SEC = 0.5

# 구간 미지정 시 끝점 완화 길이(초). 안무 구간을 모르므로 필요하다 (D19).
DEFAULT_PSI_SEC = 3.0

# 포즈를 몇 프레임마다 추정할지. 1이면 모든 프레임.
#
# **맥미니 실측으로 2를 골랐다** (pro_dancer + user_dancer, 30초 구간, 각 1회):
#
# | stride | 소요 | 단축 | 평균오차 | p90 | 지적 시간대 일치 | 상위10 재현 |
# |---|---|---|---|---|---|---|
# | 1 | 174.7초 | — | 24.92도 | 43.76 | 100% | 100% |
# | **2** | **97.0초** | **44%** | **25.71도** | 45.92 | **91%** | **100%** |
# | 3 | 75.6초 | 57% | 28.26도 | 53.43 | 81% | 100% |
#
# "상위10 재현" = 기준(stride=1)이 상위로 꼽은 구간 각각에 대해, 같은 신체 그룹을
# 같은 시간대에 짚었는지. **셋 다 100%다 — 놓치는 문제는 없다.**
#
# 3은 평균 오차가 13%, p90이 22% 나빠지고 시간대 일치도 81%로 떨어져 쓰지 않는다.
# 2는 오차 증가가 0.8도(3%)로 작다.
#
# ⚠️ **단, 상위 구간의 순서는 바뀐다.** 심각도를 그 영상 자체의 부위별 중앙값으로
# 정규화하는데, 프레임 수가 줄면 그 중앙값이 조금 움직이기 때문이다. 짚는 대목은
# 같고 우선순위만 달라진다. (stride=1을 두 번 돌린 결과는 완전히 동일했으므로
# 이 변화는 실행 간 잡음이 아니라 stride 때문이 맞다.)
#
# 되돌리려면 분석 서버에서 `DANCE_ANALYSIS_STRIDE=1`로 두고 재시작하면 된다.
DEFAULT_STRIDE = max(1, int(os.environ.get('DANCE_ANALYSIS_STRIDE', '2')))

# 동작 대응표를 몇 초 간격으로 샘플링할지.
#
# DTW 경로는 프레임 단위(30fps면 초당 30개 이상)라 그대로 내보내면 쓸데없이 크다.
# 브라우저는 이 표를 선형 보간해서 쓰므로 촘촘할 필요가 없다 — 0.1초면 사람이
# 어긋남을 느끼는 한계(대략 0.1~0.2초)보다 촘촘하다.
SYNC_MAP_INTERVAL_SEC = 0.1


def _step(cb, name):
    """진행 단계를 알린다. 콜백이 없거나 실패해도 분석은 계속돼야 한다."""
    if cb is None:
        return
    try:
        cb(name)
    except Exception:
        pass


def build_sync_map(path_pairs, ref_seq, prac_seq, interval_sec=SYNC_MAP_INTERVAL_SEC):
    """
    DTW 경로를 `[[연습 시각, 기준 시각], ...]` 목록으로 바꾼다.
    **둘 다 원본 영상 기준 초**라 브라우저가 `<video>.currentTime`에 그대로 쓸 수 있다.

    이 표가 있으면 서버에서 비교 영상을 렌더링하지 않아도 두 영상을 맞춰 볼 수 있다.
    렌더링은 분석 시간을 60~90초 더 쓰고, OpenCV가 내보내는 mp4v(MPEG-4 Part 2)는
    브라우저가 재생하지 못하는 경우가 많아 H.264 재인코딩까지 필요하다.
    표는 30초 영상 기준 300쌍 남짓(약 5KB)이고 만드는 데 드는 시간이 사실상 0이다.

    **여러 기준 프레임이 한 연습 프레임에 붙으면 평균을 쓴다.** 첫 값만 쓰면
    기준 영상이 그 구간에서 멈췄다가 튀어 오른다 — 재생으로 보면 끊겨 보인다.
    경로가 단조 증가하므로 구간별 평균도 단조 증가해서 되감김은 생기지 않는다.
    """
    if not path_pairs:
        return []

    # stride를 쓰면 프레임 인덱스가 원본과 1:1이 아니다. 초로 환산해야 맞는다
    # (시퀀스 fps가 이미 `원본 fps / stride`라 frame/fps는 항상 맞는 초다).
    u_off_sec = prac_seq.meta.get('source_start_sec', 0.0)
    s_off_sec = ref_seq.meta.get('source_start_sec', 0.0)
    prac_fps = max(prac_seq.fps, 1e-6)
    ref_fps = max(ref_seq.fps, 1e-6)

    acc = {}
    for u, s in path_pairs:
        acc.setdefault(int(u), []).append(int(s))

    us = sorted(acc)
    out, last_t = [], None
    for i, u in enumerate(us):
        pt = u / prac_fps + u_off_sec
        # 첫 점과 마지막 점은 무조건 남긴다 — 양 끝이 잘리면 보간 범위가 좁아진다.
        if last_t is not None and i != len(us) - 1 and pt - last_t < interval_sec:
            continue
        rt = (sum(acc[u]) / len(acc[u])) / ref_fps + s_off_sec
        out.append([round(pt, 3), round(rt, 3)])
        last_t = pt
    return out


def analyze(reference_video, practice_video, model_path=None,
            ref_start_sec=None, ref_end_sec=None,
            prac_start_sec=None, prac_end_sec=None,
            conf_thresh=0.5, top_issues=10, with_context=False,
            progress_cb=None, stride=None):
    """
    returns: dict — 구간 피드백과 통계

    progress_cb: 단계가 바뀔 때마다 단계 이름으로 호출된다
        ('extract_reference' / 'extract_practice' / 'align'). 분석은 한 번 시작하면
        3분 가까이 아무 신호가 없어서, 밖에서 보면 멈춘 것과 구분되지 않는다.
        추정치가 아니라 **실제로 지금 무엇을 하는 중인지**를 알리기 위한 것이다.

    with_context: True면 `'_context'` 키에 추출한 시퀀스와 DTW 경로를 담아
        반환한다. 시각화(`visualize.py`)가 포즈를 **다시 추출하지 않게** 하려는
        것이다 — 기존 경로는 분석 2회 + 영상 렌더링 2회로 총 4번 추출했다.
        JSON 직렬화가 안 되는 객체이므로 저장 전에 꺼내야 한다.

    ref_start_sec / prac_start_sec: 안무 시작 시점 (D23).
        **둘 다 주어지면** 구간 지정으로 보고 좁은 밴드를 쓰며 psi를 끈다.
        끝점은 선택이다 — 실측상 시작점만으로도 전역 오프셋이 제거되고,
        끝점의 추가 이득은 4.7%p로 작았다.

        psi를 끄는 이유: 시작점이 확정되면 좁은 밴드가 대각선을 잡아주는데,
        psi가 그 위에서 양 끝을 또 건너뛰게 해 경로를 흐트러뜨린다. 실측에서
        psi를 켜면 최대 오차가 0.73초 → 3.23초로 악화됐다.
    """
    stride = DEFAULT_STRIDE if stride is None else max(1, int(stride))

    # 영상마다 별도 프로세스에서 추출한다. 한 프로세스에서 해상도가 다른 영상을
    # 이어서 처리하면 뒤 영상의 결과가 매 실행마다 달라지기 때문이다
    # (근거는 engine/adapter.py의 "프로세스 격리 추출" 주석).
    _step(progress_cb, 'extract_reference')
    ref_seq = extract_sequence_isolated(reference_video, model_path, conf_thresh,
                                        ref_start_sec, ref_end_sec, stride)
    _step(progress_cb, 'extract_practice')
    prac_seq = extract_sequence_isolated(practice_video, model_path, conf_thresh,
                                         prac_start_sec, prac_end_sec, stride)

    _step(progress_cb, 'align')
    ref_feat = build_features(ref_seq, 'angle')
    prac_feat = build_features(prac_seq, 'angle')

    trimmed = ref_start_sec is not None and prac_start_sec is not None
    if trimmed:
        window = max(2, int(round(DEFAULT_BAND_SEC * ref_seq.fps)))
        psi = None
    else:
        window = 15
        psi = int(round(DEFAULT_PSI_SEC * prac_seq.fps))

    result = compare_sequences(
        prac_feat, ref_feat,
        window=window, psi=psi,
        max_time_diff=0.6, min_separation=0.3, min_valid_ratio=0.3)

    # 잘라낸 구간 기준 인덱스는 사용자에게 의미가 없으므로 원본 기준도 남긴다.
    #
    # **프레임 수를 더하지 않고 초로 계산한다.** stride를 쓰면 시퀀스의 프레임
    # 하나가 원본 여러 프레임에 해당해서 `frame + offset`이 어긋난다.
    # 시퀀스 fps가 이미 `원본 fps / stride`라 `frame / fps`는 항상 맞는 초가 된다.
    u_off_sec = prac_seq.meta.get('source_start_sec', 0.0)
    s_off_sec = ref_seq.meta.get('source_start_sec', 0.0)
    u_stride = prac_seq.meta.get('stride', 1)
    s_stride = ref_seq.meta.get('stride', 1)
    u_off = prac_seq.meta.get('source_start_frame', 0)
    s_off = ref_seq.meta.get('source_start_frame', 0)
    for e in result.get('details', []):
        e['user_frame_src'] = e['user_frame'] * u_stride + u_off
        e['standard_frame_src'] = e['standard_frame'] * s_stride + s_off
        e['user_t_src'] = round(e['user_frame'] / prac_seq.fps + u_off_sec, 4)
        e['standard_t_src'] = round(e['standard_frame'] / ref_seq.fps + s_off_sec, 4)

    feedback = summarize(result.get('details', []), top_n=top_issues)

    out = {
        'feedback': feedback,
        'stats': result.get('stats', {}),
        'details': result.get('details', []),
        # 두 영상을 맞춰 재생하기 위한 대응표. 항상 넣는다 — 만드는 비용이
        # 사실상 없고, 없으면 화면에서 나란히 비교를 할 수 없다.
        'sync_map': build_sync_map(result.get('path', []), ref_seq, prac_seq),
        'meta': {
            'reference_video': reference_video,
            'practice_video': practice_video,
            'reference_fps': ref_seq.fps,
            'practice_fps': prac_seq.fps,
            'trimmed': trimmed,
            'stride': stride,
            'band_sec': DEFAULT_BAND_SEC if trimmed else None,
            'window_frames': window,
            'psi_frames': psi,
            'trim': {
                'ref_start_sec': ref_start_sec, 'ref_end_sec': ref_end_sec,
                'prac_start_sec': prac_start_sec, 'prac_end_sec': prac_end_sec,
            },
            'reference_coverage': ref_seq.coverage(0.3),
            'practice_coverage': prac_seq.coverage(0.3),
            'rotation': {
                'reference': ref_seq.meta.get('rotation', 0),
                'practice': prac_seq.meta.get('rotation', 0),
            },
        },
    }

    if with_context:
        out['_context'] = {
            'reference_sequence': ref_seq,
            'practice_sequence': prac_seq,
            'path': result.get('path', []),
        }
    return out
