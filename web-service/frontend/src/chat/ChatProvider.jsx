import { useEffect, useRef, useState } from 'react';
import { chatSocketUrl, fetchCurrentUser } from '../api/client';
import { useAuth } from '../auth/useAuth';
import { ChatContext } from './chatContext';
import { ChatStore } from './chatStore';

/**
 * 로그인한 동안 채팅 연결을 하나 유지한다(탭마다 하나). 로그인 직후 연결하고, 로그아웃하면 직접 끊고
 * 보낼 목록을 지운다 — 쿠키 삭제만으로는 열린 웹소켓이 남는다(E5).
 *
 * 핸드셰이크가 실패하면 브라우저는 HTTP 상태(401)를 볼 수 없어서, 로그인이 살아 있는지 `/api/users/me`로
 * 다시 묻는다. 401일 때만 AuthProvider.refresh로 넘겨 사용자를 비우고 로그인 화면으로 보낸다 —
 * refresh는 실패하면 무조건 사용자를 비우므로, 서버가 내려간 동안(네트워크 오류) 재연결마다 부르면
 * 로그아웃되고 보낼 목록까지 지워진다.
 */
/** 빌드 환경 변수(ms). 비어 있으면 기본값, 숫자가 아니거나 음수면 0(끔) */
function envMs(value, fallback) {
    if (value === undefined || value === '') return fallback;
    const n = Number(value);
    return Number.isFinite(n) && n > 0 ? n : 0;
}

export function ChatProvider({ children }) {
    const { user, checking, refresh } = useAuth();
    const [store] = useState(() => new ChatStore({
        socketUrl: chatSocketUrl(),
        // 10/21 측정으로 채택한 기본값: 묶기 500ms, 백오프 재전송, 202 뒤 2초 확인 재전송(서버 쪽 모으기와 짝).
        // 빌드 환경 변수로 덮는다(0이면 끔, VITE_CHAT_READ_RETRY_MODE=fixed면 고정 간격)
        readThrottleMs: envMs(import.meta.env.VITE_CHAT_READ_THROTTLE_MS, 500),
        readRetryMode: import.meta.env.VITE_CHAT_READ_RETRY_MODE === 'fixed' ? 'fixed' : 'backoff',
        readConfirmMs: envMs(import.meta.env.VITE_CHAT_READ_CONFIRM_MS, 2000),
    }));

    useEffect(() => {
        let inFlight = false;
        store.setAuthCheck(async () => {
            if (inFlight) return;
            inFlight = true;
            try {
                await fetchCurrentUser();
            } catch (err) {
                if (err?.status === 401) refresh();
            } finally {
                inFlight = false;
            }
        });
    }, [store, refresh]);

    const userId = user?.id;
    const lastUserId = useRef(null);
    const nickname = user?.nickname;
    useEffect(() => {
        // 로그인 확인 중(새로 고침 직후)에는 사용자가 아직 비어 있다. 이때 지우면 새로 고침 뒤
        // "다시 보내기"로 보여 줘야 할 보낼 목록이 사라진다.
        if (checking) return undefined;
        if (userId == null) {
            // 로그인했다가 비워졌을 때(로그아웃·만료)만 지운다. 처음 확인이 실패한 것(서버가 내려가
            // 네트워크 오류)으로는 지우지 않는다 — 다음에 같은 사용자로 확인되면 "다시 보내기"로 보인다.
            if (lastUserId.current != null) store.clearOutbox();
            lastUserId.current = null;
            return undefined;
        }
        lastUserId.current = userId;
        store.start({ id: userId, nickname });
        return () => store.stop();
    }, [store, checking, userId, nickname]);

    return <ChatContext.Provider value={store}>{children}</ChatContext.Provider>;
}
