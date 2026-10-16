---
name: ODO
description: 안무 카운트보드 — 댄서가 손으로 세던 카운트를 앱이 대신 짚어준다 (연습장 크림지 + 형광펜 강조)
colors:
  cb-paper: "#f5f1e8"
  cb-paper-card: "#fbf9f3"
  cb-ink: "#2c2a26"
  cb-ink-secondary: "rgba(44, 42, 38, 0.68)"
  cb-ink-muted: "rgba(44, 42, 38, 0.44)"
  cb-rule: "rgba(44, 42, 38, 0.16)"
  cb-highlight: "#f5e211"
  cb-highlight-soft: "rgba(245, 226, 17, 0.4)"
  cb-highlight-wash: "#f8f0a8"
  cb-cyan: "#026a7d"
  cb-pink: "#b81450"
  cb-note-mild: "#fdf6d0"
  cb-note-moderate: "#f6d94a"
  cb-note-severe: "#e8a23a"
  cb-error: "#b3261e"
  cb-warning: "#92600a"
typography:
  display:
    fontFamily: "'Noto Sans KR', Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "1.6rem"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "normal"
  numeral:
    fontFamily: "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "3rem"
    fontWeight: 700
    lineHeight: 1
    fontFeature: "tabular-nums"
  title:
    fontFamily: "'Noto Sans KR', Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "1.15rem"
    fontWeight: 700
    lineHeight: 1.3
  body:
    fontFamily: "'Noto Sans KR', Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "0.9rem"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "'Noto Sans KR', Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "0.85rem"
    fontWeight: 700
    letterSpacing: "0.04em"
  script-accent:
    fontFamily: "'Nanum Pen Script', cursive"
    fontSize: "1.5rem"
    fontWeight: 400
    lineHeight: 1.3
rounded:
  sm: "4px"
spacing:
  xs: "8px"
  sm: "16px"
  md: "20px"
  lg: "24px"
  xl: "28px"
  xxl: "32px"
components:
  cell:
    backgroundColor: "{colors.cb-highlight-wash}"
    textColor: "{colors.cb-ink}"
    rounded: "{rounded.sm}"
    padding: "20px 22px 22px"
  strip:
    backgroundColor: "{colors.cb-paper-card}"
    textColor: "{colors.cb-ink}"
    rounded: "{rounded.sm}"
    padding: "20px 24px"
  guide-cta:
    backgroundColor: "{colors.cb-ink}"
    textColor: "{colors.cb-paper}"
    rounded: "{rounded.sm}"
    padding: "10px 20px"
  note-mild:
    backgroundColor: "{colors.cb-note-mild}"
    textColor: "{colors.cb-ink}"
    padding: "16px 18px"
  note-moderate:
    backgroundColor: "{colors.cb-note-moderate}"
    textColor: "{colors.cb-ink}"
    padding: "16px 18px"
  note-severe:
    backgroundColor: "{colors.cb-note-severe}"
    textColor: "{colors.cb-ink}"
    padding: "16px 18px"
---

# Design System: ODO

## Overview

**Creative North Star: "안무 카운트보드" (Choreography Count Board)**

`ODO`는 아이콘+제목+설명 카드 그리드라는 이 카테고리의 기본값을 거부한다. 댄서가 이미 손으로 세던 카운트를 앱이 대신 짚어주는 판이다: 연습장 크림지(`#f5f1e8`) 배경 위에 흑연 잉크(`#2c2a26`) 텍스트, 형광펜 옐로(`#f5e211`) 단 하나의 강조색. 유리 카드도, 네온 글로우도, 아이콘도 없다.

이 세계관은 처음 `Dashboard` 한 화면에서만 완성된 형태로 도착했고, 이번 라운드에서 두 번째 표면(`LogDetail` + 그 자식 컴포넌트 `SyncedComparison` + `VideoTrimmer`의 이 세계관 스코프 부분)으로 확장됐다. 확장은 아키텍처 변화를 하나 동반했다: 토큰과 공용 컴포넌트 재스킨(`.card`, `.btn-*`, `.badge-*`, 폼, 스피너 등)이 `Dashboard.css`에서 빠져나와 `src/styles/countboard.css` 한 파일로 모였고, `main.jsx`에서 전역으로 한 번 로드되며, 전부 `.countboard` 조상 클래스 아래로 스코프됐다. **어느 화면이든 루트 엘리먼트에 `className="... countboard"`를 붙이면 이 세계관에 들어온다** — `.card`, `.btn-primary` 같은 공용 유틸리티 클래스가 그 안에서 자동으로 재스킨되고, 아직 이관되지 않은 화면의 같은 클래스는 건드리지 않는다. 이후 화면을 옮길 때의 표준 패턴이다 (아래 "이 세계관에 합류하는 법" 참고).

Dashboard 자체도 이번 확장 과정에서 한 군데 갱신됐다: "다음 할 일" 세 칸(`cb-cells`)이 판+헤어라인 문법에서 **낱장 포스트잇** 문법으로 바뀌었다. 칸마다 `--cb-highlight-wash` 배경(칸마다 다른 색조가 아니라 One Yellow Rule을 지키는 단일 색), `nth-child`별 `--note-rotate`로 살짝 다른 각도의 회전, 오른쪽 위 모서리를 사선으로 자르는 `clip-path` 노치. 칸 사이는 공유 헤어라인 대신 실제 간격(gap)을 두어 페이지 배경의 27px 노트 격자가 그 틈으로 비쳐 보인다. Hover/focus는 회전이 0deg로 펴지며 살짝 확대되는 것으로 반응한다 — 그림자도, 색 변화도 없다(No-Shadow Rule의 연장).

`LogDetail`은 옛 다크 네온 세계관에서 이 세계관으로 전체 이관됐다. 증거 숫자 문법(Proof Numeral — Inter 고정폭, 그라디언트 없는 잉크색)이 대시보드의 `cb-strip-number`에서 `issue-count-number`로 그대로 이어진다. "지적 구간" 목록은 심각도 랭크(`severityTier()` 함수가 순위를 3등분해 상/중/하로 나눈다)로 색을 입힌 실제 포스트잇이 됐다 — 이 세계관의 One Yellow Rule과 정면으로 부딪힐 수 있었던 지점이다: 색으로 심각도를 구분하고 싶지만 새 색상(hue)을 늘릴 수는 없다. 해법은 노랑~주황 한 색 계열 안에서 **명도/채도(강도)만 3단계로 바꾸는 것**이었다(`--cb-note-mild/moderate/severe`) — 이 세계관 최초로 "색상"이 아니라 "강도"로 정보를 인코딩하는 규칙이 생겼다. 포스트잇 위에 얹히는 텍스트는 전부 완전 불투명 `--cb-ink`를 쓴다 — 옅은 톤(secondary 68%, muted 44%)은 moderate/severe 포스트잇 색 위에서 4.5:1 AA를 못 채운다는 것이 재계산으로 확인됐기 때문이다 (직접 재검증: ink-secondary/moderate ≈ 4.34:1, ink-secondary/severe ≈ 3.48:1, 둘 다 AA 미달 — full ink는 세 포스트잇 색 전부에서 6.6:1 이상).

`SyncedComparison`이 이 세계관으로 옮겨오며, 옛 네온 세계관에서 넘어온 시안(기준/레퍼런스)·핑크(나의 시도) 기능색 매핑이 드디어 양쪽 다 실제로 쓰이는 화면이 생겼다. Dashboard에는 비교 배지가 없어 시안만 링크 hover·포커스 링에 쓰였지만, 여기서 핑크(`#b81450`)가 "내 영상" 태그, 박자 어긋남 그래프의 헤드 라인, 빠름/느림 상태 텍스트로 처음 실전 배치된다. 이 세계관의 Reference/Practice Rule이 완성되는 지점이다.

`VideoTrimmer`는 아직 이관되지 않은 `Practice` 화면과 공유하는 컴포넌트라, 이번 확장에서 스코프 재정의만 추가됐다(`.countboard .video-trimmer` 등) — 컴포넌트 자체의 기본(스코프 없는) 규칙은 옛 세계관 그대로 남고, `.countboard` 조상 안에서 렌더될 때만 새 세계관 스타일을 얻는다. 부분 이관 중인 공유 컴포넌트를 다루는 표준 패턴이다.

**Key Characteristics:**
- 연습장 크림 배경 + 흑연 잉크 텍스트 + 형광펜 옐로 단일 강조, 시안(기준)·핑크(나의 시도)는 기능색으로 완결
- 공용 토큰·재스킨은 `styles/countboard.css` 한 곳에 모이고, `.countboard` 클래스가 화면을 이 세계관에 편입시킨다
- Count Cells는 판+헤어라인이 아니라 회전된 포스트잇 — 칸마다 각도가 다르고 모서리가 사선으로 잘린다
- 지적 구간(포스트잇)은 심각도를 새 색상이 아니라 노랑~주황 한 계열의 강도로 표현한다
- Hover/focus는 그림자가 아니라 배경 워시 전환(Dashboard 칸 내부) 또는 회전 원복(포스트잇 컴포넌트)으로만 반응한다
- 27px 간격 헤어라인 리피팅 그래디언트로 만든 노트 격자 텍스처가 화면 전체 배경에 깔린다
- 손글씨풍 서체(Nanum Pen Script)는 화면 전체에서 딱 한 곳에만 절제해서 등장
- 증거 숫자만 Inter 고정폭(tabular-nums)으로 명시 고정

## Colors

크림 종이 위에 잉크, 그 위에 단 하나의 형광펜 — 색의 숫자를 줄인 만큼 그 하나가 뜻을 가진다. 기능색(시안/핑크)과 심각도 색(포스트잇 3단)은 정보 전달 전용이며 장식적 강조와는 역할이 다르다.

### Primary
- **형광펜 옐로 Highlight** (`#f5e211`): 유일한 장식적 강조색. 카운트 스트립 링크의 밑줄, `::selection`, 숫자가 갱신될 때의 스윕 애니메이션에 쓰인다. 배경으로는 절대 그대로 깔리지 않는다 — 배경으로 쓸 땐 대비 재계산을 거친 `cb-highlight-wash`(`#f8f0a8`, 불투명)로 갈아탄다. Dashboard의 Count Cells는 이제 이 워시를 기본 배경으로 상시 사용한다(더 이상 hover 전용이 아니다).

### Secondary
- **눌린 시안 Cyan** (`#026a7d`): "기준/레퍼런스" 기능색. 크림 배경에서 4.5:1이 나오도록 재조정된 값. 링크 hover, 셀 `focus-visible` 아웃라인, `SyncedComparison`의 기준 영상 태그·박자 그래프 라인·"빠름" 상태 텍스트에 쓰인다.
- **나의 시도 핑크 Pink** (`#b81450`): "나의 시도/연습" 기능색. `SyncedComparison`에서 처음 실전 배치됐다 — 내 영상 태그, 지적 구간 타임라인 마커, 박자 그래프 헤드 라인, "느림" 상태 텍스트. Dashboard에는 비교 배지가 없어 이 색의 자리가 없었다.

### Tertiary
- **심각도 포스트잇 Note Mild/Moderate/Severe** (`#fdf6d0` / `#f6d94a` / `#e8a23a`): 지적 구간 목록의 배경색. 셋 다 노랑~주황 한 색 계열의 명도·채도 단계이며, 서로 다른 hue가 아니다 — "심각도 색을 새로 늘리고 싶지만 두 번째 강조색은 만들 수 없다"는 실제 긴장을 색상이 아닌 강도로 해결한 결과다 (The Severity-by-Intensity Rule 참고).

### Neutral
- **연습장 크림 Paper** (`#f5f1e8`, `cb-paper`): 페이지 배경.
- **카드 크림 Paper Card** (`#fbf9f3`, `cb-paper-card`): 스트립·칸·안내 박스의 표면 — 배경보다 살짝 밝다.
- **흑연 잉크 Ink** (`#2c2a26`, `cb-ink`): 제목·숫자·본문 강조 텍스트. 색 있는 포스트잇 위에서는 옅은 톤 대신 이 완전 불투명 값만 쓴다.
- **잉크 68% Ink Secondary** (`rgba(44,42,38,0.68)`): 본문·설명·라벨의 기본 색. 크림/카드 크림 배경 위에서만 쓴다 — moderate/severe 포스트잇 위에서는 재계산 결과 AA(4.5:1)에 못 미친다(≈4.34:1 / ≈3.48:1).
- **잉크 44% Ink Muted** (`rgba(44,42,38,0.44)`): 본문 텍스트로 쓰기엔 대비가 부족해 테두리류 비-텍스트 용도로만 남는다.
- **헤어라인 Rule** (`rgba(44,42,38,0.16)`): 격자 텍스처, 칸 사이 구분선, 스트립·안내 박스 보더.
- **에러 Error** (`#b3261e`) / **경고 Warning** (`#92600a`): 옛 네온 세계관에서 넘어온 상태 기능색을 크림 배경용으로 조정한 값. 오류 배너, 경고 문구에 쓰인다.

### Named Rules
**The One Yellow Rule.** 장식적 강조는 이 세계관에서 형광펜 옐로 단 하나뿐이다. 새 강조가 필요할 때 두 번째 장식 액센트 색을 만들지 않는다 — 형광펜 위치를 늘리거나(밑줄, 워시, 스윕), 절제된 잉크 굵기로 해결한다. 시안·핑크·포스트잇 3단은 이 규칙의 예외가 아니라 애초에 다른 범주다 — 전부 정보를 나르는 기능색이지, 장식적 강조가 아니다.

**The Wash-vs-Soft Rule.** 배경으로 칠하는 형광펜(hover 워시)과 텍스트 밑에 까는 형광펜(숫자 스윕)은 서로 다른 토큰이다 — `cb-highlight-wash`(불투명)는 배경 전용, `cb-highlight-soft`(반투명)는 오버레이 전용. 반투명 값을 배경색으로 그대로 쓰면 밑에 깔린 격자선과 탁하게 섞여 대비가 무너진다.

**The Severity-by-Intensity Rule.** 지적 구간의 심각도(상/중/하)는 색상(hue)이 아니라 한 색 계열 안의 강도로 표현한다(`cb-note-mild`→`cb-note-moderate`→`cb-note-severe`, 전부 노랑~주황). 심각도마다 다른 hue를 쓰지 않는다 — 그러면 One Yellow Rule이 사실상 무력화된다.

**The Full-Ink-on-Color Rule.** 색 있는 포스트잇(`cb-note-*`) 배경 위의 텍스트는 항상 완전 불투명 `cb-ink`를 쓴다. `ink-secondary`/`ink-muted`의 옅은 톤은 크림/카드 크림 배경 전용이다 — moderate·severe 포스트잇 위에서는 AA 대비를 못 채운다.

**The Reference/Practice Rule.** 시안은 항상 기준/레퍼런스, 핑크는 항상 나의 시도/연습을 가리킨다. 이 매핑은 옛 세계관에서 넘어온 것이며, 이 세계관 안에서는 `SyncedComparison`이 유일하게 두 역할을 동시에 쓰는 곳이다.

## Typography

**Body/Display Font:** `Noto Sans KR` (한글, korean 서브셋 셀프호스팅)
**Numeral Font:** `Inter` (라틴/숫자 전용, latin + latin-ext 서브셋)
**Signature Accent Font:** `Nanum Pen Script` (korean 서브셋) — 안내 제목 한 곳에만

**Character:** 한글 워크호스는 Noto Sans KR, 숫자는 Inter로 명시적으로 고정한 이중 서체 체계다. Noto Sans KR의 korean 서브셋에는 라틴 숫자용 unicode-range가 없어, 고정하지 않으면 화면에서 가장 중요한 증거 숫자가 조용히 한글 서체로 그려진다 — 그래서 `.cb-strip-number`와 `.issue-count-number` 둘 다 `font-family: Inter, ...`를 다시 선언한다. Nanum Pen Script는 기울인 고딕이 아니라 실제 손글씨 서체이며, 화면 전체에서 단 한 곳(Dashboard 첫 방문자 안내 제목)에만 쓰고 `-1deg` 회전으로 손으로 쓴 인상을 준다.

### Hierarchy
- **Display** (700, 1.6rem, 1.3): `h1` 인사말. 그라디언트 클리핑 없이 순수 잉크색.
- **Numeral** (700, 3rem→2.25rem@480px, 1, tabular-nums, Inter 고정): Proof Numeral — 이 세계관의 증거 숫자 문법. Dashboard의 카운트 스트립 숫자(`cb-strip-number`, 3rem)와 LogDetail의 지적 구간 개수(`issue-count-number`, 4rem)가 같은 문법(그라디언트 없음, Inter 고정폭)을 공유한다.
- **Title** (700, 1.15rem, 1.3): 칸 제목(`cb-cell-title`).
- **Body** (400, 0.9~0.95rem, 1.5): 칸 설명, 스트립 라벨/상태 문구.
- **Label** (700, 0.85rem, letter-spacing 0.04em): 칸 번호(`01`/`02`/`03`), 지적 구간 순위 배지 — 대문자 표기 없이 숫자로 위계를 만든다.
- **Script Accent** (400, 1.5rem, cursive, -1deg 회전): 안내 박스 제목 한 곳 전용.

### Named Rules
**The Proof Numeral Rule.** 화면에서 가장 중요한 증거 숫자(다듬을 구간 개수)는 Inter로 명시 고정하고 `font-variant-numeric: tabular-nums`를 건다. 그라디언트 클리핑 없이 순수 잉크색이다. 한글 서체 폴백에 맡기지 않는다 — 자릿수가 바뀌어도 숫자 폭이 흔들리지 않아야 "증거"로 읽힌다. 이 문법은 화면마다 반복되는 표면 패턴이지 한 화면 전용 규칙이 아니다(Dashboard의 `cb-strip-number` + LogDetail의 `issue-count-number`).

**The One Script Rule.** 손글씨풍 서체는 화면당 한 곳으로 제한한다. 두 곳 이상에 쓰면 손글씨가 장식이 되어 이 세계관의 절제가 무너진다.

## Layout

칸 그리드는 `repeat(auto-fit, minmax(220px, 1fr))`로 반응형 열 수를 결정하고, 리더 전용 칸(`cb-cells-leader`)은 단일 열·최대폭 340px로 별도 판을 이룬다. 스트립과 칸 사이 간격은 32px, 칸끼리는 24px 20px(gap) — 판 문법이던 시절의 1px 헤어라인 gap과 달리 실제 여백이다. 페이지 상단 패딩 40px, 하단 60px. 480px 이하에서 스트립이 세로로 쌓이고 증거 숫자가 3rem→2.25rem로 줄어든다.

배경의 27px 간격 헤어라인 리피팅 그래디언트가 노트 격자 텍스처를 만든다 — `.countboard` 클래스가 배경·서체·격자를 한 번에 공급하므로, 이 세계관에 합류하는 어떤 화면도 같은 텍스처 위에서 시작한다.

### 이 세계관에 합류하는 법

1. 페이지 루트 엘리먼트의 `className`에 `countboard`를 추가한다(예: `"log-detail-page page countboard"`).
2. `.card`, `.btn-primary`, `.btn-secondary`, `.btn-outline`, `.badge-*`, `.form-*`, `.spinner`, `.auth-error`, `.text-gradient` 등 공용 유틸리티 클래스는 자동으로 재스킨된다 — `styles/countboard.css`가 `.countboard` 아래에서 재정의를 이미 갖고 있기 때문이다. 페이지별 CSS를 새로 쓸 필요가 없다.
3. 페이지 고유 선택자(예: `.issue-item`, `.cb-cell`)에서 옛 `--accent-*`/`--text-*`/`--bg-*` 토큰을 `--cb-*` 토큰으로 바꿔 쓴다.
4. 여러 페이지가 공유하는 컴포넌트(`VideoTrimmer`처럼)는 컴포넌트 CSS 파일 안에 `.countboard .컴포넌트클래스 { ... }` 스코프 재정의만 추가한다 — 기본(스코프 없는) 규칙은 그대로 두어 아직 이관되지 않은 화면에서 계속 옛 세계관을 쓰게 한다.

## Elevation & Depth

그림자를 쓰지 않는다. 깊이·상태·상호작용은 헤어라인, 배경색 전환, 회전 원복, 밑줄 두께로만 표현한다.

- **Count Cells (Dashboard):** 그림자 대신 배경 워시(`cb-highlight-wash`)를 상시 배경으로 깔고, 개별 칸은 살짝 다른 각도로 회전해 있다. Hover/focus는 각도가 0deg로 펴지며 1.015배 확대되는 것으로 반응한다 — 색이나 그림자는 바뀌지 않는다.
- **지적 구간 포스트잇 (LogDetail):** 그림자 대신 hover 시 `translateX(2px)`로만 반응한다. 지금 반복 재생 중인 항목은 그림자가 아니라 잉크색 안쪽 아웃라인(`outline: 2px solid var(--cb-ink); outline-offset: -2px`)으로 표시한다.

### Named Rules
**The No-Shadow Rule.** 이 세계관에 `box-shadow`는 존재하지 않는다. 깊이·상태·강조는 헤어라인, 배경색 전환, 회전 원복, 밑줄 두께로만 표현한다. 옛 네온 세계관의 글로우(`--shadow-glow`)를 들여오지 않는다 — `VideoTrimmer`의 `.countboard` 스코프 재정의가 `marker-start`/`marker-end`의 `box-shadow`를 명시적으로 `none`으로 지우는 것이 그 증거다.

## Shapes

반경은 4px 하나뿐이다. 스트립·판·안내 박스·CTA 버튼·`.card`·폼 필드에 일괄 적용된다. 옛 시스템의 12px/20px 두 단계나 pill/circle 반경은 이 세계관에 없다. 안내 박스만 예외적으로 점선 보더(`1px dashed`)를 쓴다.

**뜯긴 모서리(Torn Corner) 노치.** 포스트잇 성격의 컴포넌트(Dashboard의 Count Cells, LogDetail의 지적 구간 항목)는 반경 대신 오른쪽 위 모서리를 사선으로 잘라내는 동일한 `clip-path: polygon(0 0, calc(100% - 14px) 0, 100% 14px, 100% 100%, 0 100%)`를 공유한다 — 종이를 뜯어낸 낱장처럼 보이게 하는, 두 화면에서 반복되는 형태 문법이다.

## Components

### Count Strip (Signature)
카운트 스트립은 Dashboard의 증거 컴포넌트다. 배경 `cb-paper-card`, 1px 헤어라인 보더, 4px 반경, 패딩 20px 24px. 최근 분석이 완료 상태면 큰 Inter 숫자(3rem/700)를 형광펜 스윕 애니메이션(0.6s ease-out, `scaleX(0)→1`)과 함께 보여준다. 진행 중/대기/실패/빈 상태는 각각 다른 한 줄 문구로 대체된다.

### Count Cells — Sticky Notes (Signature, revised)
Dashboard의 "다음 할 일" 세 칸. 판+헤어라인 문법에서 낱장 포스트잇 문법으로 바뀌었다. 배경은 `cb-highlight-wash` 상시 적용, 칸마다 `--note-rotate`(-1.5deg/1deg/-0.5deg)로 다른 각도, 오른쪽 위 뜯긴 모서리 노치, 아이콘 대신 두 자리 칸 번호(`01`/`02`/`03`). Hover/focus는 각도가 펴지고 살짝 확대되는 것으로만 반응한다(No-Shadow Rule). focus-visible은 추가로 시안 아웃라인을 얻는다.

### Severity Sticky Note (Signature, LogDetail)
지적 구간 목록의 각 항목. 같은 뜯긴 모서리 노치를 쓰지만 배경색이 심각도 랭크(상/중/하)에 따라 `cb-note-severe`/`cb-note-moderate`/`cb-note-mild`로 바뀐다(The Severity-by-Intensity Rule). 텍스트는 항상 완전 불투명 잉크(The Full-Ink-on-Color Rule). Hover는 `translateX(2px)`, 활성(반복 재생 중) 상태는 잉크색 안쪽 아웃라인.

### Proof Numeral (Signature pattern, cross-screen)
그라디언트 없는 순수 잉크색 + Inter tabular-nums 고정폭 대형 숫자. Dashboard의 `cb-strip-number`(3rem)와 LogDetail의 `issue-count-number`(4rem)가 같은 문법을 공유한다 — 이 세계관에서 "증거"로 읽혀야 하는 숫자는 전부 이 패턴을 따른다.

### Guide Box
첫 기록이 없는 사용자에게만 나타나는 안내(Dashboard). 배경 `cb-paper-card`, 1px 점선 헤어라인 보더, 4px 반경, 패딩 24px. 제목만 손글씨 서체(Nanum Pen Script, -1deg 회전). CTA는 잉크색 배경 + 크림 텍스트의 솔리드 버튼.

### Synced Comparison (Signature, LogDetail)
기준 영상과 연습 영상을 나란히 재생하며 박자 어긋남을 그래프로 보여주는 이 세계관 최초의 비교 컴포넌트. 기준 영상 태그는 시안(`rgba(2,106,125,0.9)` 배경), 내 영상 태그는 핑크(`rgba(184,20,80,0.9)` 배경) — Reference/Practice Rule이 실전 배치되는 유일한 곳. 박자 어긋남 그래프의 기준선은 시안, 현재 위치 헤드 라인은 핑크. 지적 구간 타임라인 마커도 핑크 계열 반투명이다. 트랙·오디오 셀렉트 등 주변 UI는 `cb-paper-card`/`cb-rule`로 이 세계관의 중립 팔레트를 따른다.

### Navigation
Dashboard의 칸 자체가 내비게이션 링크다 (`<Link className="cb-cell">`) — 별도 버튼이나 화살표 없이 칸 전체가 클릭 영역이다.

## Do's and Don'ts

### Do:
- **Do** 장식적 강조는 형광펜 옐로 하나로 제한한다 (The One Yellow Rule).
- **Do** 배경용 형광펜과 오버레이용 형광펜을 구분한다 — `cb-highlight-wash`는 배경, `cb-highlight-soft`는 텍스트 위 오버레이 (The Wash-vs-Soft Rule).
- **Do** 심각도·단계처럼 순서가 있는 정보를 색으로 표현해야 할 때는 새 hue가 아니라 한 색 계열의 강도로 표현한다 (The Severity-by-Intensity Rule).
- **Do** 색 있는 포스트잇 배경 위에서는 옅은 톤이 아니라 완전 불투명 잉크만 쓴다 (The Full-Ink-on-Color Rule).
- **Do** 증거 숫자는 Inter + tabular-nums로 명시 고정한다, 한글 서체 폴백에 맡기지 않는다 (The Proof Numeral Rule).
- **Do** 깊이는 헤어라인, 배경 전환, 회전 원복으로만 표현한다. `box-shadow`를 새로 추가하지 않는다 (The No-Shadow Rule).
- **Do** 손글씨 서체는 화면당 한 곳으로 제한한다 (The One Script Rule).
- **Do** 새 화면을 이 세계관으로 옮길 때는 루트에 `countboard` 클래스를 붙이고, 공용 유틸리티는 `styles/countboard.css`의 재정의를 그대로 상속받게 한다.
- **Do** 여러 화면이 공유하는 컴포넌트는 `.countboard` 스코프 재정의만 추가하고 기본 규칙은 그대로 둔다 (부분 이관 중인 화면을 위해).
- **Do** 시안은 기준/레퍼런스, 핑크는 나의 시도/연습에만 쓴다 (The Reference/Practice Rule).

### Don't:
- **Don't** 칸마다 개별 카드 그림자를 주지 않는다 — 그림자는 이 세계관 전체에서 금지다. Dashboard의 finish review에서 개별 카드 그림자 시도가 정정된 이력이 있다.
- **Don't** 아이콘(이모지든 SVG든)을 칸/구간 구분에 쓰지 않는다 — 두 자리 번호나 심각도 순위 배지가 그 역할을 한다.
- **Don't** 이 화면의 시안 값(`#026a7d`)·핑크 값(`#b81450`)을 옛 네온 세계관의 시안(`#00d9ff`)·핑크와 섞어 쓰지 않는다 — 크림 배경용으로 대비를 다시 계산한 별개 값이다.
- **Don't** Noto Sans KR 서브셋에 숫자를 맡기지 않는다 — korean 서브셋에는 라틴 숫자 unicode-range가 없어 조용히 잘못된 서체로 렌더링된다.
- **Don't** 심각도·상태 구분에 새로운 hue를 추가하지 않는다 — 노랑~주황 강도 단계로 해결한다(Severity-by-Intensity Rule). 두 번째 강조색이 필요하다고 느껴지면 먼저 강도·굵기·위치로 풀 수 있는지 검토한다.
- **Don't** 색 있는 포스트잇 배경 위에 `ink-secondary`/`ink-muted` 옅은 톤 텍스트를 놓지 않는다 — moderate/severe 배경에서 AA 대비를 못 채운다(재검증: 각 ≈4.34:1, ≈3.48:1).

## Migration Status

**이 세계관은 현재 `Dashboard.jsx`, `LogDetail.jsx`, `LogDetail` 안에서만 쓰이는 `SyncedComparison.jsx`, 그리고 `VideoTrimmer`의 `.countboard`-스코프 부분에 적용됐다.** 나머지 화면은 아직 이관되지 않았다 — 위 토큰·규칙은 이관된 표면에만 적용되는 것으로 취급한다.

**아직 옛 "The Studio After Hours"(다크 네온) 세계관을 그대로 쓰는 화면/컴포넌트** (grep으로 재검증: `.countboard` 문자열은 `main.jsx`, `styles/countboard.css`, `components/VideoTrimmer.css`, `pages/LogDetail.jsx`/`.css`, `pages/Dashboard.jsx`/`.css`에서만 나타난다):
- `Header` (로고·내비게이션)
- `Login`, `Signup`
- `Practice` (여기서 쓰는 `VideoTrimmer`는 `.countboard` 조상이 없으므로 그대로 옛 세계관 스타일을 받는다)
- `Assignments`, `AssignmentDetail`, `AssignmentCreate`
- `Logs`
- `VideoUploader`
- 전역 `index.css` / `App.css`의 `:root` 토큰(`--bg-primary`, `--accent-pink`, `--accent-cyan`, `--accent-gradient`, `--shadow-glow` 등)

이 화면들에는 이 문서의 `colors`/`typography`/`components` 토큰이 적용되지 않는다 — 여전히 옛 시스템(Signal Pink/Cyan/Purple, 유리 카드, 네온 글로우)을 따른다. 다음에 저 화면 중 하나를 열어 이관하는 에이전트는, 페이지 루트에 `countboard` 클래스를 붙이고 공용 유틸리티 재스킨을 `styles/countboard.css`에서 자동으로 상속받는 패턴(위 "이 세계관에 합류하는 법")을 그대로 따르면 된다.
