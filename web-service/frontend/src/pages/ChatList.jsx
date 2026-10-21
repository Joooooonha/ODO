import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { MessageCircle, User, Users } from 'lucide-react';
import { useChat } from '../chat/useChat';
import { formatListTime, previewText } from '../chat/format';
import '../components/Chat.css';

/**
 * 채팅 목록 — 팀 방과 DM을 한 목록에 최근 순으로. 메시지 없는 DM은 숨긴다(E1, 저장소의 roomList).
 * 목록은 연결 직후 GET /rooms로 채우고, 그 뒤로는 방 구독으로 들어오는 메시지가 미리보기를 바꾼다.
 */
export default function ChatList() {
    const chat = useChat();
    const location = useLocation();
    const navigate = useNavigate();
    // 방에서 돌려보내질 때 넘어온 안내("더 이상 참여할 수 없어요"). 한 번만 읽는다.
    const [notice] = useState(() => location.state?.notice ?? '');

    useEffect(() => {
        if (location.state?.notice) navigate(location.pathname, { replace: true, state: null });
    }, [location, navigate]);

    const rooms = chat.roomList();

    return (
        <div className="page">
            <div className="container chat-container">
                <h1 className="chat-page-title">채팅</h1>
                {notice && <div className="auth-error">{notice}</div>}

                {!chat.roomsLoaded ? (
                    <div className="loading"><div className="spinner"></div></div>
                ) : rooms.length === 0 ? (
                    <div className="empty-state">
                        <span className="empty-state-icon"><MessageCircle size={48} /></span>
                        <h3 className="empty-state-title">아직 대화가 없어요</h3>
                        <p className="empty-state-text">팀에 참여하면 팀 채팅방이 생겨요.</p>
                    </div>
                ) : (
                    <ul className="chat-list">
                        {rooms.map((room) => {
                            const unread = chat.unread(room);
                            return (
                                <li key={room.roomId}>
                                    <Link to={`/chats/${room.roomId}`} className="chat-list-row">
                                        <span className={`chat-list-icon${room.type === 'TEAM' ? ' team' : ''}`}>
                                            {room.type === 'TEAM' ? <Users size={20} /> : <User size={20} />}
                                        </span>
                                        <span className="chat-list-body">
                                            <span className="chat-list-top">
                                                <span className="chat-list-name">{room.displayName}</span>
                                                <span className="chat-list-time">{formatListTime(room.preview?.createdAt)}</span>
                                            </span>
                                            <span className="chat-list-bottom">
                                                <span className="chat-list-preview">{previewText(room.preview)}</span>
                                                {unread > 0 && (
                                                    <span className="chat-unread" aria-label={`안 읽은 메시지 ${unread}개`}>
                                                        {unread > 99 ? '99+' : unread}
                                                    </span>
                                                )}
                                            </span>
                                        </span>
                                    </Link>
                                </li>
                            );
                        })}
                    </ul>
                )}
            </div>
        </div>
    );
}
