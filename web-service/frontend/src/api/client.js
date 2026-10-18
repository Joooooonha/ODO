/**
 * API Client - 백엔드 통신 유틸리티
 */

// 배포에서는 프론트와 API가 같은 도메인이라 빈 문자열이면 된다
// (`/api/...`가 그대로 같은 오리진으로 나간다).
// 로컬 개발은 Vite(5173)와 Spring(8080)이 다른 포트라 절대 주소가 필요하다.
// 값은 빌드 시점에 주입한다: VITE_API_BASE_URL=https://내도메인 npm run build
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/**
 * 현재 사용자 정보 (화면 표시용 캐시).
 *
 * **인증에는 쓰이지 않는다.** 인증은 서버가 구운 httpOnly 쿠키로만 이뤄지고,
 * 자바스크립트는 그 토큰을 볼 수 없다. 여기 담긴 값은 닉네임 표시 같은 용도이며,
 * 임의로 고쳐도 권한이 바뀌지 않는다.
 */
export const getCurrentUser = () => {
  const userStr = localStorage.getItem('user');
  if (userStr) {
    return JSON.parse(userStr);
  }
  return null;
};

export const setCurrentUser = (user) => {
  localStorage.setItem('user', JSON.stringify(user));
};

/**
 * 로그아웃 — 서버에서 쿠키를 지우고 로컬 캐시도 비운다.
 * 캐시만 지우면 쿠키가 남아 여전히 로그인 상태다.
 */
export const logout = async () => {
  try {
    await fetchAPI('/api/auth/logout', { method: 'POST' });
  } finally {
    localStorage.removeItem('user');
  }
};

/**
 * 기본 fetch 래퍼
 */
const fetchAPI = async (endpoint, options = {}) => {
  const headers = {
    ...options.headers,
  };

  // JSON 요청인 경우 Content-Type 추가
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }

  const response = await fetch(`${API_BASE_URL}${endpoint}`, {
    ...options,
    headers,
    // httpOnly 쿠키를 실어 보내려면 반드시 필요하다.
    // 예전에는 X-User-Id 헤더로 사용자를 알렸는데, 그건 클라이언트가 값을
    // 정하는 구조라 헤더만 바꾸면 남의 계정이 됐다.
    credentials: 'include',
  });

  if (!response.ok) {
    // 쿠키가 없거나 만료됐다. 남아 있는 캐시를 지워 화면이 로그인된 것처럼
    // 보이지 않게 한다.
    if (response.status === 401) {
      localStorage.removeItem('user');
    }
    const errorText = await response.text();
    const error = new Error(errorText || `HTTP ${response.status}`);
    // 화면이 오류를 구분해 안내할 수 있게 상태와 서버의 오류 코드를 붙인다.
    // message는 예전처럼 응답 본문 그대로 둔다 — 기존 화면이 그 값을 보여준다.
    error.status = response.status;
    try {
      const body = JSON.parse(errorText);
      error.code = body.code;
      error.serverMessage = body.error;
    } catch {
      // 본문이 JSON이 아니면 code 없이 둔다
    }
    throw error;
  }

  // 응답이 비어있는 경우 처리
  const contentType = response.headers.get('content-type');
  if (contentType && contentType.includes('application/json')) {
    return response.json();
  }

  return response.text();
};

// ============ Auth API ============

/**
 * 회원가입
 */
export const signup = async (data) => {
  return fetchAPI('/api/auth/signup', {
    method: 'POST',
    body: JSON.stringify(data),
  });
};

/**
 * 로그인 (개발 환경 전용).
 *
 * 운영에서는 이 엔드포인트가 등록되지 않는다(404). 소셜 로그인만 사용한다.
 * 성공하면 서버가 JWT를 httpOnly 쿠키로 구워준다 — 응답 본문에 토큰은 없다.
 */
export const login = async (loginId, password) => {
  return fetchAPI('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ loginId, password }),
  });
};

/** 소셜 로그인 시작. 브라우저를 공급자로 보낸다. */
export const socialLoginUrl = (provider) =>
  `${API_BASE_URL}/oauth2/authorization/${provider}`;

/**
 * 현재 사용자 정보 조회
 */
export const fetchCurrentUser = async () => {
  return fetchAPI('/api/users/me');
};

// ============ Push API ============

/**
 * VAPID 공개키. 인증 없이 열려 있다 — 이름 그대로 공개해도 되는 값이고,
 * 알림 UI를 그릴지 판단하려면 로그인 전에도 필요하다.
 * 서버에 키가 설정되지 않았으면 빈 문자열이 온다.
 */
export const getPushPublicKey = async () => {
  return fetchAPI('/api/push/public-key');
};

/** 브라우저가 만든 구독 정보를 서버에 맡긴다. 서버는 이걸로 푸시를 보낸다. */
export const savePushSubscription = async (subscription) => {
  return fetchAPI('/api/push/subscribe', {
    method: 'POST',
    body: JSON.stringify(subscription),
  });
};

export const deletePushSubscription = async (endpoint) => {
  return fetchAPI(`/api/push/subscribe?endpoint=${encodeURIComponent(endpoint)}`, {
    method: 'DELETE',
  });
};

// ============ Team API ============
//
// 실패하면 Error에 status와 code(서버 ErrorCode)가 붙는다.
// 예: 참여 시 404 JOIN_CODE_NOT_FOUND / 409 ALREADY_MEMBER / 403 KICKED_FROM_TEAM

/** 내가 활동 중인 팀 목록: [{ teamId, name, myRole, memberCount }] */
export const getMyTeams = async () => {
  return fetchAPI('/api/teams');
};

/** 팀 생성. 만든 사람이 팀장이 된다. */
export const createTeam = async (name) => {
  return fetchAPI('/api/teams', {
    method: 'POST',
    body: JSON.stringify({ name }),
  });
};

/** 참여 전 확인: { name, leaderNickname, memberCount } */
export const previewJoin = async (code) => {
  return fetchAPI('/api/teams/join-preview', {
    method: 'POST',
    body: JSON.stringify({ code }),
  });
};

/** 코드로 참여: { teamId, name, myRole, memberCount } */
export const joinTeam = async (code) => {
  return fetchAPI('/api/teams/join', {
    method: 'POST',
    body: JSON.stringify({ code }),
  });
};

/** Team Space 상단 한 줄: { teamId, name, myRole, memberCount } */
export const getTeam = async (teamId) => {
  return fetchAPI(`/api/teams/${teamId}`);
};

/** 활동 중 멤버: [{ userId, nickname, role }] — 팀장이 맨 앞 */
export const getTeamMembers = async (teamId) => {
  return fetchAPI(`/api/teams/${teamId}/members`);
};

/** 참여 코드 (팀장만): { joinCode } */
export const getJoinCode = async (teamId) => {
  return fetchAPI(`/api/teams/${teamId}/join-code`);
};

export const leaveTeam = async (teamId) => {
  return fetchAPI(`/api/teams/${teamId}/leave`, { method: 'POST' });
};

export const kickMember = async (teamId, userId) => {
  return fetchAPI(`/api/teams/${teamId}/members/${userId}/kick`, { method: 'POST' });
};

// ============ Mission API ============
//
// 실패하면 Error에 status와 code가 붙는다. 삭제된 미션은 404 MISSION_DELETED.
// 영상은 먼저 uploadVideo(file, 'REFERENCE'|'PRACTICE', {}, onProgress, 'MISSION')로 올리고 id만 보낸다.

/** 미션 탭 목록: [{ missionId, title, submittedCount, targetCount, myStatus, createdAt }] */
export const getMissions = async (teamId) => {
  return fetchAPI(`/api/teams/${teamId}/missions`);
};

/** { title, description, referenceVideoId, targetUserIds } → { missionId } */
export const createMission = async (teamId, data) => {
  return fetchAPI(`/api/teams/${teamId}/missions`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
};

/** 미션 상세: { missionId, teamId, title, description, referenceVideoId, writerNickname,
 *  createdAt, myRole, myStatus, targets: [{ userId, nickname, submissionId }] } */
export const getMission = async (missionId) => {
  return fetchAPI(`/api/missions/${missionId}`);
};

/** 제목·안내 텍스트·레퍼런스 영상을 통째로 바꾼다. 대상자는 바꿀 수 없다. */
export const updateMission = async (missionId, data) => {
  return fetchAPI(`/api/missions/${missionId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
};

export const deleteMission = async (missionId) => {
  return fetchAPI(`/api/missions/${missionId}`, { method: 'DELETE' });
};

/** { videoId, content } → { submissionId } */
export const submitMission = async (missionId, data) => {
  return fetchAPI(`/api/missions/${missionId}/submissions`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
};

/** 제출물: { submissionId, missionId, submitterUserId, submitterNickname, videoId, content,
 *  createdAt, canComment, comments: [{ commentId, writerUserId, writerNickname, content, createdAt }] } */
export const getSubmission = async (submissionId) => {
  return fetchAPI(`/api/submissions/${submissionId}`);
};

export const addComment = async (submissionId, content) => {
  return fetchAPI(`/api/submissions/${submissionId}/comments`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  });
};

// ============ Video API ============

/**
 * 영상 업로드 — **파일이 백엔드를 거치지 않는다.**
 *
 * 1. 서버에서 서명된 업로드 URL을 받고
 * 2. 브라우저가 저장소로 직접 PUT 하고
 * 3. 완료를 서버에 알린다
 *
 * 예전에는 FormData로 백엔드에 통째로 보냈는데, 100MB짜리 영상이 서버
 * 대역폭·디스크를 쓰고 Cloudflare 프록시의 요청 본문 100MB 제한에도 걸린다.
 *
 * 응답: { videoId, logId, analyzable }
 * - `logId`는 연습 영상에서만 채워진다. 기준 영상은 "따라 할 대상"이지
 *   누군가의 연습이 아니므로 연습 기록을 만들지 않는다 → null.
 * - **videoId를 logId로 유추하지 말 것.** 두 값은 실제로 어긋난다.
 *
 * @param onProgress 0~1 진행률 콜백 (선택)
 * @param purpose    'GENERAL'(자유 연습, 기본) 또는 'MISSION'. Mission 영상은 분석하지 않고
 *                   연습 기록도 만들지 않는다 — 완료 응답의 logId는 늘 null이다.
 */
export const uploadVideo = async (file, type, link = {}, onProgress, purpose = 'GENERAL') => {
  // 1) 업로드 URL 발급
  const { videoId, uploadUrl } = await fetchAPI('/api/videos/upload-url', {
    method: 'POST',
    body: JSON.stringify({
      filename: file.name,
      contentType: file.type || 'video/mp4',
      type,
      purpose,
    }),
  });

  // 2) 저장소로 직접 업로드.
  //    fetch 대신 XHR을 쓰는 이유는 업로드 진행률을 알기 위해서다
  //    (fetch는 업로드 진행 이벤트를 주지 않는다). 수십 MB라 표시가 필요하다.
  await new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', uploadUrl);
    // Content-Type은 **서명에 포함**되므로 발급 때와 같은 값을 보내야 한다.
    xhr.setRequestHeader('Content-Type', file.type || 'video/mp4');
    xhr.upload.onprogress = (e) => {
      if (onProgress && e.lengthComputable) onProgress(e.loaded / e.total);
    };
    xhr.onload = () =>
      (xhr.status >= 200 && xhr.status < 300)
        ? resolve()
        : reject(new Error(`업로드 실패 (HTTP ${xhr.status})`));
    xhr.onerror = () => reject(new Error('업로드 중 네트워크 오류가 발생했습니다.'));
    xhr.send(file);
  });

  // 3) 완료 통보 — 이때 연습 기록이 만들어진다
  return fetchAPI(`/api/videos/${videoId}/complete`, {
    method: 'POST',
    body: JSON.stringify(link),
  });
};

/**
 * 다시 쓸 수 있는 내 영상 목록.
 *
 * 기준 영상을 매번 다시 올리지 않기 위한 것이다. 같은 안무를 반복 연습하는 것이
 * 이 서비스의 용도인데, 그때마다 같은 파일을 다시 업로드하고 분석 서버가 같은
 * 영상에서 포즈를 다시 뽑았다.
 *
 * 응답: [{ videoId, name, uploadedAt, startSec, endSec }]
 * — `name`은 이 컬럼이 생기기 전에 올린 영상에는 없어 null일 수 있다.
 */
export const getMyVideos = async (type = 'REFERENCE') => {
  return fetchAPI(`/api/videos?type=${type}`);
};

/**
 * 영상 재생 URL.
 *
 * 예전에는 `?userId=`를 붙였다. `<video src>`가 HTTP 헤더를 못 붙인다는 제약
 * 때문이었는데, **URL의 숫자만 바꾸면 남의 영상이 재생되는** 구멍이었다.
 * 인증이 쿠키로 바뀌면서 브라우저가 `<video src>` 요청에도 쿠키를 자동으로
 * 실어 보내므로 그 우회로가 필요 없어졌다.
 */
export const getVideoUrl = (videoId) => `${API_BASE_URL}/api/videos/${videoId}`;

/**
 * 영상 삭제
 */
export const deleteVideo = async (videoId) => {
  return fetchAPI(`/api/videos/${videoId}`, {
    method: 'DELETE',
  });
};

// ============ Practice Log API ============

/**
 * 연습 기록 목록 조회
 */
export const getPracticeLogs = async (page = 0, size = 10) => {
  return fetchAPI(`/api/practice-logs?page=${page}&size=${size}`);
};

/**
 * 연습 기록 상세 조회
 */
export const getPracticeLog = async (logId) => {
  return fetchAPI(`/api/practice-logs/${logId}`);
};

/**
 * 분석 진행 단계 조회.
 *
 * 상세 조회와 **따로 부른다.** 진행 단계는 분석 서버에 물어봐야 알 수 있는데,
 * 그걸 상세 조회에 넣으면 분석 서버가 꺼져 있을 때 결과 화면 전체가 열리지
 * 않는다. 이건 없어도 되는 정보이므로 실패하면 그냥 무시한다.
 *
 * 응답: { status, stage, label, pct, nextPct, elapsedSec }
 * — 분석 서버에 닿지 못했거나 다른 작업이 돌고 있으면 stage가 null이다.
 */
export const getAnalysisProgress = async (logId) => {
  return fetchAPI(`/api/practice-logs/${logId}/progress`);
};

/**
 * 안무 구간 지정 (트림)
 *
 * 네 값 모두 선택이다. null/undefined인 필드는 보내지 않는다 —
 * 백엔드가 "미지정"과 "0초"를 다르게 처리하므로 0으로 채우면 안 된다.
 */
export const updateTrim = async (logId, range) => {
  const body = {};
  for (const key of [
    'referenceStartSec', 'referenceEndSec',
    'practiceStartSec', 'practiceEndSec',
  ]) {
    if (range[key] !== null && range[key] !== undefined) {
      body[key] = range[key];
    }
  }
  return fetchAPI(`/api/practice-logs/${logId}/trim`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
};

/**
 * 분석 요청
 *
 * 분석은 비동기다. 이 호출은 시작만 시키고 즉시 반환한다
 * (AnalysisAcceptedResponse: status, trimApplied, pollUrl, message).
 * 실제 완료 여부는 getPracticeLog(logId)를 폴링해서 status가
 * COMPLETED/FAILED가 될 때까지 확인해야 한다. 실측 소요는 약 2분이다.
 */
export const analyzeLog = async (logId) => {
  return fetchAPI(`/api/practice-logs/${logId}/analyze`, {
    method: 'POST',
  });
};

// ============ Chat API ============
//
// 메시지 전송은 HTTP가 아니라 웹소켓(STOMP)으로 한다(src/chat/chatStore.js).
// 실패하면 Error에 status와 code가 붙는다: 403 NOT_CHAT_MEMBER / 403 DM_NOT_ALLOWED / 400 CANNOT_DM_SELF

/**
 * 채팅 웹소켓 주소. API 주소에서 만든다 — 운영은 빈 문자열(같은 오리진)이라 지금 페이지의 호스트를 쓴다.
 * 인증은 핸드셰이크에 실리는 httpOnly 쿠키로 한다(자바스크립트는 토큰을 다루지 않는다).
 */
export const chatSocketUrl = () => {
  if (API_BASE_URL) {
    return API_BASE_URL.replace(/^http/, 'ws') + '/api/ws';
  }
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${window.location.host}/api/ws`;
};

/**
 * 내 방 목록(최근 순): [{ roomId, type, displayName, teamId, peerUserId, lastSeq,
 *  lastMessage: { seq, type, senderId, senderNickname, content, createdAt, targetNickname } | null,
 *  myReadSeq, unread, empty }]
 * 메시지 없는 DM은 empty: true로 온다 — 화면은 숨기되 구독은 한다.
 */
export const getChatRooms = async () => {
  return fetchAPI('/api/chat/rooms');
};

/**
 * 메시지 조회(항상 오름차순): { roomId, lastSeq, messages: [...] }
 * 옵션 없음 → 최신 50 / afterSeq → 그 이후 / beforeSeq → 그 이전.
 */
export const getChatMessages = async (roomId, { afterSeq, beforeSeq, limit } = {}) => {
  const params = new URLSearchParams();
  if (afterSeq != null) params.set('afterSeq', afterSeq);
  if (beforeSeq != null) params.set('beforeSeq', beforeSeq);
  if (limit != null) params.set('limit', limit);
  const query = params.toString();
  return fetchAPI(`/api/chat/rooms/${roomId}/messages${query ? `?${query}` : ''}`);
};

/** DM 열기. 이미 있으면 그 방, 없으면 같은 팀일 때만 만든다: { roomId, created } */
export const openDm = async (userId) => {
  return fetchAPI('/api/chat/dms', {
    method: 'POST',
    body: JSON.stringify({ userId }),
  });
};
