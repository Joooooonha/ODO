"""단위 비용 결합 회귀의 계수 신뢰구간(Task-chat-read-improve 4절 "함께 처리할 검증").

입력: unit-cost.json(시나리오 1 r ≥ 5 + 시나리오 2 (나) r ≤ 800의 단계별 앱 CPU(코어, 2대 합)·메시지/초·보고/초·프레임/초)
  python3 -I analysis/unit-cost-bootstrap.py results/raw/win/s2-1000x4/unit-cost.json [추가 rows.json ...]
모형
  M3: 코어 = a + b·메시지 + c·보고
  M4: M3 + d·프레임                       ("프레임당 비용 ≈ 0"이 구간으로도 맞나)
  M5: M3 + e·보고·(방 인원 − 4)            (보고 1건 비용 중 방 크기에 비례하는 부분: 스냅샷 행 수 등)
신뢰구간: 단계(행) 재표집 bootstrap 20,000번, 2.5~97.5 백분위수. 고정 시드
"""
import json
import sys

import numpy as np

ROOM = {'s1-1x19': 20, 's1-20x0': 20, 's1-10x10': 20, 's2-1000x4': 4}


def load(paths):
    rows = []
    for p in paths:
        d = json.load(open(p))
        rows += d['rows'] if isinstance(d, dict) else d
    keep = [r for r in rows if (r['n'].startswith('s1') and r['r'] >= 5) or (r['n'].startswith('s2') and r['r'] <= 800)
            or r.get('include')]
    return keep


def design(rows, model):
    cols = [np.ones(len(rows)), [r['msg'] for r in rows], [r['rep'] for r in rows]]
    if model == 'M4':
        cols.append([r['fr'] for r in rows])
    if model == 'M5':
        cols.append([r['rep'] * (r.get('members', ROOM.get(r['n'], 4)) - 4) for r in rows])
    return np.column_stack(cols)


def fit(X, y):
    beta, *_ = np.linalg.lstsq(X, y, rcond=None)
    return beta


def main():
    rows = load(sys.argv[1:])
    y = np.array([r['cores'] for r in rows])
    rng = np.random.default_rng(20261020)
    out = {'n': len(rows)}
    names = {'M3': ['a(코어)', 'b(ms/메시지)', 'c(ms/보고)'], 'M4': ['a(코어)', 'b(ms/메시지)', 'c(ms/보고)', 'd(µs/프레임)'],
             'M5': ['a(코어)', 'b(ms/메시지)', 'c(ms/보고, 4명 방)', 'e(ms/보고/인원)']}
    scale = {'a(코어)': 1, 'd(µs/프레임)': 1e6}
    for model in ['M3', 'M4', 'M5']:
        X = design(rows, model)
        beta = fit(X, y)
        resid = y - X @ beta
        r2 = 1 - (resid @ resid) / ((y - y.mean()) @ (y - y.mean()))
        boots = []
        for _ in range(20000):
            idx = rng.integers(0, len(rows), len(rows))
            Xb = X[idx]
            if np.linalg.matrix_rank(Xb) < X.shape[1]:
                continue
            boots.append(fit(Xb, y[idx]))
        boots = np.array(boots)
        res = {'R2': round(float(r2), 4), 'bootstrapSamples': len(boots), 'coef': {}}
        for i, name in enumerate(names[model]):
            k = scale.get(name, 1e3)
            lo, hi = np.percentile(boots[:, i], [2.5, 97.5])
            res['coef'][name] = {'value': round(float(beta[i]) * k, 4), 'ci95': [round(float(lo) * k, 4), round(float(hi) * k, 4)]}
        out[model] = res
    print(json.dumps(out, ensure_ascii=False, indent=2))


main()
