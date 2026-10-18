import { useContext, useSyncExternalStore } from 'react';
import { ChatContext } from './chatContext';

/**
 * 채팅 저장소를 읽는다. 저장소가 바뀔 때마다(메시지·연결 상태·보낼 목록) 다시 그린다.
 * `ChatProvider` 안에서만 쓸 수 있다.
 */
export function useChat() {
    const store = useContext(ChatContext);
    if (!store) throw new Error('useChat은 ChatProvider 안에서만 쓸 수 있습니다.');
    useSyncExternalStore(store.subscribe, store.getVersion);
    return store;
}
