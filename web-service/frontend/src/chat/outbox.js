/**
 * 보낼 목록(outbox) 저장. **탭별 sessionStorage**에 둔다 — 새로 고침에는 남고 탭끼리는 섞이지 않는다(9.5).
 * 주인(userId)과 함께 저장한다 — 같은 탭에서 다른 계정으로 로그인하면 남의 메시지를 보내지 않는다.
 * 저장소를 못 쓰는 환경(사생활 보호 모드 등)에서는 메모리에만 둔다.
 */
const KEY = 'odo.chat.outbox.v2';   // { userId, items }

/** 그 사용자의 것만 돌려준다. 다른 사용자가 남긴 것(같은 탭에서 계정을 바꾼 경우)은 버린다. */
export function loadOutbox(userId) {
  try {
    const raw = window.sessionStorage.getItem(KEY);
    const saved = raw ? JSON.parse(raw) : null;
    if (!saved || saved.userId !== userId || !Array.isArray(saved.items)) return [];
    return saved.items;
  } catch {
    return [];
  }
}

export function saveOutbox(userId, items) {
  try {
    if (items.length === 0 || userId == null) {
      window.sessionStorage.removeItem(KEY);
    } else {
      window.sessionStorage.setItem(KEY, JSON.stringify({ userId, items }));
    }
  } catch {
    // 저장하지 못해도 이 탭이 열려 있는 동안은 메모리의 목록으로 동작한다
  }
}
