"""구간 피드백을 눈으로 확인할 수 있게 그린다.

초기 프로토타입의 `report_generator.py` / `video_renderer.py`(삭제됨)를 대체한다.
**기존 두 모듈에는 같은 결함이 있었다: DTW 정렬 결과를 쓰지 않는다.**

    # report_generator.generate_report
    ref_frame  = self._get_frame(ref_cap,  frame_idx)
    prac_frame = self._get_frame(prac_cap, frame_idx)   # ← 같은 인덱스

    # video_renderer.create_analysis_video
    ret1, ref_frame  = ref_cap.read()
    ret2, prac_frame = prac_cap.read()                  # ← 나란히 한 칸씩

둘 다 기준 영상 N번 프레임과 연습 영상 N번 프레임을 짝지어 보여준다.
정렬을 하는 이유 자체가 두 영상의 같은 동작이 서로 다른 시각에 있기 때문인데,
정작 사람이 보는 결과물에서는 그 정렬이 버려지고 있었다. 시작 시점이 3초만
어긋나도 화면의 두 사람은 전혀 다른 동작을 하고 있게 된다.

여기서는 DTW 경로로 짝지어진 프레임을 보여준다.

추가로 기존 경로는 포즈를 **4번 추출**했다(분석 2회 + 영상 렌더링 2회).
여기서는 이미 뽑아둔 `PoseSequence`를 그대로 받아 재추출하지 않는다.
"""
import cv2
import numpy as np
from pathlib import Path

from engine.sequence import joint_map
from engine.video_utils import apply_rotation, open_video

# 골격 연결. 관절 "이름"으로 정의해서 백엔드(17개/33개)에 무관하게 동작한다.
BONES = [
    ('l_shoulder', 'r_shoulder'), ('l_hip', 'r_hip'),
    ('l_shoulder', 'l_hip'), ('r_shoulder', 'r_hip'),
    ('l_shoulder', 'l_elbow'), ('l_elbow', 'l_wrist'),
    ('r_shoulder', 'r_elbow'), ('r_elbow', 'r_wrist'),
    ('l_hip', 'l_knee'), ('l_knee', 'l_ankle'),
    ('r_hip', 'r_knee'), ('r_knee', 'r_ankle'),
]

# 피드백 부위 → 강조할 뼈. `engine.features`의 SEGMENTS/BENDS와 같은 정의다.
# 굽힘(bend)은 그 관절에 붙은 두 뼈를 함께 칠해야 "굽힘"으로 읽힌다.
PART_BONES = {
    'l_upper_arm': [('l_shoulder', 'l_elbow')],
    'r_upper_arm': [('r_shoulder', 'r_elbow')],
    'l_forearm': [('l_elbow', 'l_wrist')],
    'r_forearm': [('r_elbow', 'r_wrist')],
    'l_thigh': [('l_hip', 'l_knee')],
    'r_thigh': [('r_hip', 'r_knee')],
    'l_shin': [('l_knee', 'l_ankle')],
    'r_shin': [('r_knee', 'r_ankle')],
    'l_elbow_bend': [('l_shoulder', 'l_elbow'), ('l_elbow', 'l_wrist')],
    'r_elbow_bend': [('r_shoulder', 'r_elbow'), ('r_elbow', 'r_wrist')],
    'l_knee_bend': [('l_hip', 'l_knee'), ('l_knee', 'l_ankle')],
    'r_knee_bend': [('r_hip', 'r_knee'), ('r_knee', 'r_ankle')],
    'torso_tilt': [('l_shoulder', 'r_shoulder'), ('l_hip', 'r_hip'),
                   ('l_shoulder', 'l_hip'), ('r_shoulder', 'r_hip')],
    'neck': [('l_shoulder', 'r_shoulder')],
}

COLOR_OK = (150, 220, 120)      # BGR — 정상
COLOR_BAD = (60, 60, 240)       # BGR — 지적된 부위
COLOR_REF = (200, 200, 200)     # 기준 영상은 회색조로 (비교 대상일 뿐)


def _pixels(seq, frame_idx):
    """
    정규화 좌표를 픽셀로 되돌린다.

    `build_sequence`가 x·y를 모두 **높이 단위**로 저장하므로(등방 정규화)
    양쪽 다 H를 곱하면 원래 픽셀 위치가 된다.

    returns: {관절이름: (x, y)} — 유효한 관절만
    """
    if frame_idx < 0 or frame_idx >= seq.n_frames:
        return {}
    H, _W = seq.meta.get('frame_size', (0, 0))
    jm = joint_map(seq.num_keypoints)
    out = {}
    for name, k in jm.items():
        if seq.valid[frame_idx, k]:
            x, y = seq.coords[frame_idx, k]
            out[name] = (int(round(float(x) * H)), int(round(float(y) * H)))
    return out


def _draw_skeleton(frame, pts, bad_bones=(), base_color=COLOR_OK, thickness=4):
    """관절 점과 뼈를 그린다. `bad_bones`에 든 뼈만 빨간색."""
    bad = {tuple(sorted(b)) for b in bad_bones}
    for a, b in BONES:
        if a not in pts or b not in pts:
            continue
        color = COLOR_BAD if tuple(sorted((a, b))) in bad else base_color
        cv2.line(frame, pts[a], pts[b], color, thickness, cv2.LINE_AA)
    for p in pts.values():
        cv2.circle(frame, p, max(3, thickness), base_color, -1, cv2.LINE_AA)
    return frame


def _read_frames(video_path, wanted):
    """
    필요한 프레임만 골라 읽는다. **탐색(seek) 대신 순차 읽기**를 쓴다 —
    가변 GOP mp4에서 `CAP_PROP_POS_FRAMES` 탐색은 요청한 프레임을 정확히
    주지 않는 경우가 있고, 여기서는 프레임이 1개만 밀려도 다른 동작이 된다.

    wanted: 프레임 인덱스 집합 (원본 기준)
    returns: {frame_idx: BGR 배열}
    """
    if not wanted:
        return {}
    cap, _fps, rotation = open_video(video_path)
    last = max(wanted)
    out, idx = {}, -1
    try:
        while idx < last:
            ret, frame = cap.read()
            if not ret:
                break
            idx += 1
            if idx in wanted:
                out[idx] = apply_rotation(frame, rotation)
    finally:
        cap.release()
    return out


def _label(img, text, color=(255, 255, 255)):
    """상단에 반투명 띠를 깔고 글자를 얹는다. (OpenCV는 한글을 못 그린다)"""
    h, w = img.shape[:2]
    band = img[:44].copy()
    cv2.rectangle(band, (0, 0), (w, 44), (0, 0, 0), -1)
    img[:44] = cv2.addWeighted(band, 0.55, img[:44], 0.45, 0)
    cv2.putText(img, text, (14, 31), cv2.FONT_HERSHEY_SIMPLEX, 0.8, color, 2,
                cv2.LINE_AA)
    return img


def _side_by_side(ref_img, prac_img):
    """높이를 맞춰 좌(기준)·우(연습)로 붙인다."""
    h = min(ref_img.shape[0], prac_img.shape[0], 720)
    def fit(im):
        s = h / im.shape[0]
        return cv2.resize(im, (max(1, int(im.shape[1] * s)), h))
    a, b = fit(ref_img), fit(prac_img)
    gap = np.full((h, 4, 3), 40, dtype=np.uint8)
    return np.hstack([a, gap, b])


def _pick_representative(details, issue):
    """
    구간을 대표할 프레임 하나를 고른다 — 구간 안에서 **오차가 가장 큰** 프레임.
    평균이 아니라 최대를 쓰는 이유는, 사용자가 "무엇이 틀렸는지" 보려면 가장
    두드러진 순간을 봐야 하기 때문이다.

    returns: detail dict 또는 None
    """
    lo, hi = issue['start_sec'], issue['end_sec']
    inside = [d for d in details
              if lo <= d.get('user_t_src', d['user_t']) <= hi]
    if not inside:
        return None
    return max(inside, key=lambda d: d['frame_error'])


def render_issue_images(reference_video, practice_video, ref_seq, prac_seq,
                        details, issues, output_dir, timestamp):
    """
    지적 구간마다 **DTW로 짝지어진** 기준/연습 프레임 비교 이미지를 만든다.

    반환: [(issue_index, 이미지 경로), ...]
    issues에 `representative` 정보를 채워 넣는다(부작용).

    output_dir은 임시 디렉터리여도 된다 — 결과물은 저장소로 업로드된 뒤
    호출부가 지운다.
    """
    images_dir = Path(output_dir) / 'images'
    images_dir.mkdir(parents=True, exist_ok=True)

    picks = []
    for i, issue in enumerate(issues):
        d = _pick_representative(details, issue)
        if d is not None:
            picks.append((i, issue, d))

    if not picks:
        return []

    ref_frames = _read_frames(reference_video,
                              {d['standard_frame_src'] for _, _, d in picks})
    prac_frames = _read_frames(practice_video,
                               {d['user_frame_src'] for _, _, d in picks})

    results = []
    for i, issue, d in picks:
        rf = ref_frames.get(d['standard_frame_src'])
        pf = prac_frames.get(d['user_frame_src'])
        if rf is None or pf is None:
            continue

        bad = []
        for part in issue.get('parts', []):
            bad.extend(PART_BONES.get(part, []))

        rf = _draw_skeleton(rf.copy(), _pixels(ref_seq, d['standard_frame']),
                            base_color=COLOR_REF)
        pf = _draw_skeleton(pf.copy(), _pixels(prac_seq, d['user_frame']), bad)

        _label(rf, f"REF {d['standard_t_src']:.2f}s")
        _label(pf, f"YOU {d['user_t_src']:.2f}s  err {d['frame_error']:.0f}deg")

        path = images_dir / f'issue_{timestamp}_{i:02d}.jpg'
        cv2.imwrite(str(path), _side_by_side(rf, pf), [cv2.IMWRITE_JPEG_QUALITY, 88])

        issue['representative'] = {
            'practice_frame': d['user_frame_src'],
            'practice_sec': d['user_t_src'],
            'reference_frame': d['standard_frame_src'],
            'reference_sec': d['standard_t_src'],
            'frame_error_deg': round(d['frame_error'], 1),
        }
        results.append((i, str(path)))

    return results


def render_comparison_video(reference_video, practice_video, ref_seq, prac_seq,
                            path_pairs, issues, output_dir, timestamp):
    """
    DTW로 짝지어진 프레임을 좌우로 붙인 비교 영상.

    경로는 단조 증가하므로 **양쪽 모두 순차 읽기 한 번**으로 끝난다.
    기준 영상은 되감지 않고 필요한 지점까지 앞으로만 읽어 마지막 프레임을
    유지한다(같은 기준 프레임에 여러 연습 프레임이 대응할 때).
    """
    u_off = prac_seq.meta.get('source_start_frame', 0)
    s_off = ref_seq.meta.get('source_start_frame', 0)
    # 프레임을 솎아 추출했으면 시퀀스 인덱스 1개가 원본 stride개에 해당한다.
    u_stride = prac_seq.meta.get('stride', 1)
    s_stride = ref_seq.meta.get('stride', 1)

    # 연습 프레임 → 기준 프레임 (여러 개면 첫 대응). 둘 다 원본 기준 인덱스.
    u2s = {}
    for u, s in path_pairs:
        u2s.setdefault(u * u_stride + u_off, s * s_stride + s_off)
    if not u2s:
        return None

    # 지적 구간에 걸린 연습 프레임 → 강조할 뼈
    bad_by_frame = {}
    for issue in issues:
        bones = []
        for part in issue.get('parts', []):
            bones.extend(PART_BONES.get(part, []))
        lo = int(round(issue['start_sec'] * prac_seq.fps))
        hi = int(round(issue['end_sec'] * prac_seq.fps))
        for f in range(lo, hi + 1):
            bad_by_frame.setdefault(f, []).extend(bones)

    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f'comparison_{timestamp}.mp4'

    ref_cap, _rf, ref_rot = open_video(reference_video)
    prac_cap, prac_fps, prac_rot = open_video(practice_video)
    writer = None
    ref_idx, ref_frame = -1, None

    try:
        u_frames = sorted(u2s)
        lo_u, hi_u = u_frames[0], u_frames[-1]
        u_idx = -1
        while u_idx < hi_u:
            ret, pf = prac_cap.read()
            if not ret:
                break
            u_idx += 1
            if u_idx < lo_u or u_idx not in u2s:
                continue

            want_ref = u2s[u_idx]
            while ref_idx < want_ref:
                ok, rf = ref_cap.read()
                if not ok:
                    break
                ref_idx += 1
                ref_frame = rf
            if ref_frame is None:
                continue

            rimg = apply_rotation(ref_frame, ref_rot).copy()
            pimg = apply_rotation(pf, prac_rot).copy()

            _draw_skeleton(rimg, _pixels(ref_seq, (want_ref - s_off) // s_stride),
                           base_color=COLOR_REF)
            _draw_skeleton(pimg, _pixels(prac_seq, (u_idx - u_off) // u_stride),
                           bad_by_frame.get(u_idx, []))
            _label(rimg, f'REF {(want_ref / max(ref_seq.fps, 1)):.2f}s')
            _label(pimg, f'YOU {(u_idx / max(prac_fps, 1)):.2f}s')

            canvas = _side_by_side(rimg, pimg)
            if writer is None:
                h, w = canvas.shape[:2]
                writer = cv2.VideoWriter(
                    str(out_path), cv2.VideoWriter_fourcc(*'mp4v'),
                    prac_fps or 30.0, (w, h))
            writer.write(canvas)
    finally:
        ref_cap.release()
        prac_cap.release()
        if writer is not None:
            writer.release()

    return str(out_path) if writer is not None else None
