import { Fragment, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useChat } from '../chat/useChat';
import {
    MAX_MESSAGE_LENGTH, codePointLength, formatDateLine, formatTime, localDateKey, systemSentence,
} from '../chat/format';
import './Chat.css';

/**
 * 채팅방 한 개 — 채팅방 화면과 Team Space 채팅 탭이 함께 쓴다.
 *
 * - 메시지는 저장소가 정렬해 둔 "기준부터 빈틈 없이 이어진 곳까지"만 그린다. 빈틈 뒤의 메시지는
 *   채워질 때까지 그리지 않는다(9.3)
 * - 위로 끝까지 올리면 이전 50개를 더 불러온다(스크롤 위치는 그대로)
 * - 내가 보낸 미확정 메시지는 아래에 보낸 순서대로: "전송 중" / "전송 실패 · 다시 보내기" /
 *   (새로 고침 뒤) "보내지 않은 메시지 · 다시 보내기". 말풍선마다 오류를 띄우지 않는다(9.1)
 * - 차단(REVOKED)되면 onRevoked로 알린다 — 부른 쪽이 안내 후 목록으로 보낸다
 */
export default function ChatRoomView({ roomId, onRevoked, compact = false }) {
    const chat = useChat();
    const room = chat.rooms.get(roomId);
    const messages = chat.visibleMessages(roomId);
    const pending = chat.outboxOf(roomId);
    const revoked = chat.revokedRooms.has(roomId);
    const myId = chat.user?.id;

    const listRef = useRef(null);
    const stickToBottom = useRef(true);
    const prependAnchor = useRef(null);
    const [draft, setDraft] = useState('');

    useEffect(() => {
        chat.openRoom(roomId);
        return () => chat.closeRoom(roomId);
    }, [chat, roomId]);

    useEffect(() => {
        if (revoked && onRevoked) onRevoked();
    }, [revoked, onRevoked]);

    const lastKey = `${messages.length ? messages[messages.length - 1].seq : 0}:${pending.length}`;
    const firstSeq = messages.length ? messages[0].seq : 0;

    // 새 메시지: 맨 아래에 있었으면 따라 내려간다. 과거를 불러와 위에 붙었으면 보던 위치를 지킨다.
    useLayoutEffect(() => {
        const el = listRef.current;
        if (!el) return;
        if (prependAnchor.current != null) {
            el.scrollTop = el.scrollHeight - prependAnchor.current;
            prependAnchor.current = null;
        } else if (stickToBottom.current) {
            el.scrollTop = el.scrollHeight;
        }
    }, [lastKey, firstSeq]);

    const onScroll = () => {
        const el = listRef.current;
        if (!el) return;
        stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
        if (el.scrollTop < 40 && room?.hasOlder && !room.loadingOlder) {
            prependAnchor.current = el.scrollHeight - el.scrollTop;
            chat.loadOlder(roomId);
        }
    };

    const length = codePointLength(draft);
    const tooLong = length > MAX_MESSAGE_LENGTH;
    const windowFull = chat.windowFull();
    const canSend = draft.trim().length > 0 && !tooLong && !windowFull && !revoked;

    const submit = () => {
        if (!canSend) return;
        if (chat.send(roomId, draft)) {
            setDraft('');
            stickToBottom.current = true;
        }
    };

    const onKeyDown = (e) => {
        // 한글 입력 중(조합 중) Enter는 글자 확정이지 전송이 아니다
        if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
            e.preventDefault();
            submit();
        }
    };

    if (revoked) {
        return <div className="auth-error">이 대화방에 더 이상 참여할 수 없어요.</div>;
    }

    const unstable = chat.status !== 'connected';

    return (
        <div className={`chat-room${compact ? ' chat-room-compact' : ''}`}>
            {unstable && (
                <div className="chat-banner" role="status">연결이 불안정해요. 다시 시도하는 중이에요</div>
            )}

            <div className="chat-messages" ref={listRef} onScroll={onScroll}>
                {room?.loading && messages.length === 0 && (
                    <div className="loading"><div className="spinner"></div></div>
                )}
                {room?.hasOlder && (
                    <button type="button" className="chat-older" onClick={() => {
                        const el = listRef.current;
                        if (el) prependAnchor.current = el.scrollHeight - el.scrollTop;
                        chat.loadOlder(roomId);
                    }} disabled={room.loadingOlder}>
                        {room.loadingOlder ? '불러오는 중...' : '이전 메시지 더 보기'}
                    </button>
                )}
                {room?.error && <div className="auth-error">{room.error}</div>}
                {!room?.loading && messages.length === 0 && pending.length === 0 && (
                    <p className="chat-empty">아직 메시지가 없어요. 첫 메시지를 보내 보세요.</p>
                )}

                {messages.map((m, i) => {
                    const prev = messages[i - 1];
                    const newDay = !prev || localDateKey(prev.createdAt) !== localDateKey(m.createdAt);
                    return (
                        <Fragment key={m.seq}>
                            {newDay && <div className="chat-date-line"><span>{formatDateLine(m.createdAt)}</span></div>}
                            {m.type === 'SYSTEM' ? (
                                <div className="chat-system">{systemSentence(m.content, m.targetNickname)}</div>
                            ) : (
                                <MessageBubble
                                    mine={m.senderId === myId}
                                    nickname={m.senderNickname}
                                    showName={!prev || prev.type === 'SYSTEM' || prev.senderId !== m.senderId || newDay}
                                    content={m.content}
                                    meta={formatTime(m.createdAt)}
                                />
                            )}
                        </Fragment>
                    );
                })}

                {pending.map((item) => (
                    <MessageBubble
                        key={item.clientMessageId}
                        mine
                        content={item.content}
                        state={item.state}
                        meta={item.state === 'pending' ? '전송 중' : null}
                        actions={item.state !== 'pending' && (
                            <span className="chat-bubble-actions">
                                {item.state === 'held' ? '보내지 않은 메시지 · ' : '전송 실패 · '}
                                {item.error !== 'NOT_MEMBER' && item.error !== 'INVALID_MESSAGE' && (
                                    <button type="button" onClick={() => chat.retry(item.clientMessageId)}
                                        disabled={windowFull}>다시 보내기</button>
                                )}
                                <button type="button" onClick={() => chat.discard(item.clientMessageId)}>삭제</button>
                            </span>
                        )}
                    />
                ))}
            </div>

            <form className="chat-input" onSubmit={(e) => { e.preventDefault(); submit(); }}>
                {windowFull && <div className="chat-input-notice">서버 응답이 늦어지고 있어요</div>}
                <textarea
                    className="form-textarea chat-textarea"
                    rows={2}
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    onKeyDown={onKeyDown}
                    placeholder="메시지를 입력하세요 (Shift+Enter 줄바꿈)"
                    aria-label="메시지"
                />
                <div className="chat-input-bar">
                    <span className={`chat-counter${tooLong ? ' over' : ''}`}>
                        {length.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}
                    </span>
                    <button type="submit" className="btn btn-primary btn-sm" disabled={!canSend}>보내기</button>
                </div>
            </form>
        </div>
    );
}

function MessageBubble({ mine, nickname, showName, content, meta, state, actions }) {
    return (
        <div className={`chat-row${mine ? ' mine' : ''}`}>
            {!mine && showName && <span className="chat-sender">{nickname}</span>}
            <div className="chat-bubble-line">
                <div className={`chat-bubble${state && state !== 'pending' ? ' failed' : ''}${state === 'pending' ? ' pending' : ''}`}>
                    {content}
                </div>
                {meta && <span className="chat-meta">{meta}</span>}
            </div>
            {actions}
        </div>
    );
}
