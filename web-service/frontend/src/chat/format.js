/**
 * 채팅 화면의 글자 수·시각·안내 문장.
 *
 * 글자 수는 **코드 포인트**로 센다(`[...s].length`). 서버(Java codePointCount)·DB(char_length)와 같은
 * 기준이라 이모지가 2자로 세어지지 않는다(E6).
 */

export const MAX_MESSAGE_LENGTH = 2000;

export const codePointLength = (text) => [...(text ?? '')].length;

/** 메시지 시각 HH:mm — 서버가 준 UTC 순간을 브라우저 현지 시간대로. */
export const formatTime = (iso) => {
  if (!iso) return '';
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false });
};

/** 날짜 구분선 비교용(현지 날짜). */
export const localDateKey = (iso) => {
  const d = new Date(iso);
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
};

export const formatDateLine = (iso) => {
  const d = new Date(iso);
  return `${d.getFullYear()}년 ${d.getMonth() + 1}월 ${d.getDate()}일`;
};

/** 방 목록의 시각: 오늘이면 HH:mm, 아니면 M월 D일. */
export const formatListTime = (iso) => {
  if (!iso) return '';
  const d = new Date(iso);
  if (localDateKey(iso) === localDateKey(new Date().toISOString())) return formatTime(iso);
  return `${d.getMonth() + 1}월 ${d.getDate()}일`;
};

const EVENTS = {
  JOIN: '들어왔습니다',
  LEAVE: '나갔습니다',
  KICK: '내보내졌습니다',
};

/**
 * 시스템 메시지 문장. 서버는 사건({"event":"KICK","targetUserId":7})만 저장하고, 대상의 현재 닉네임을
 * targetNickname으로 함께 준다.
 */
export const systemSentence = (content, targetNickname) => {
  let event = '';
  try {
    event = JSON.parse(content)?.event ?? '';
  } catch {
    return '알 수 없는 안내';
  }
  const who = targetNickname || '알 수 없는 사용자';
  return `${who}님이 ${EVENTS[event] ?? '바뀌었습니다'}`;
};

/** 방 목록 미리보기 한 줄. */
export const previewText = (message) => {
  if (!message) return '';
  if (message.type === 'SYSTEM') return systemSentence(message.content, message.targetNickname);
  return message.content;
};

/** 메시지마다 붙이는 UUID. randomUUID는 보안 컨텍스트(https·localhost)에서만 있다. */
export const newClientMessageId = () => {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID();
  const b = window.crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
};
