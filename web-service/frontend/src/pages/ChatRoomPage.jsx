import { useCallback } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { ChevronLeft } from 'lucide-react';
import { useChat } from '../chat/useChat';
import ChatRoomView from '../components/ChatRoomView';
import '../components/Chat.css';

/** 채팅방 화면(/chats/:roomId). 팀 방이든 DM이든 같다. 차단되면 목록으로 돌려보낸다. */
export default function ChatRoomPage() {
    const { roomId: param } = useParams();
    const roomId = Number(param);
    const chat = useChat();
    const navigate = useNavigate();
    const room = chat.rooms.get(roomId);

    const onRevoked = useCallback(() => {
        navigate('/chats', { replace: true, state: { notice: '이 대화방에 더 이상 참여할 수 없어요.' } });
    }, [navigate]);

    if (!Number.isInteger(roomId) || roomId <= 0) return <Navigate to="/chats" replace />;

    return (
        <div className="page chat-page">
            <div className="container chat-container">
                <div className="chat-room-top">
                    <Link to="/chats" className="chat-back" aria-label="채팅 목록으로"><ChevronLeft size={22} /></Link>
                    <h1>{room?.displayName || '채팅'}</h1>
                </div>
                <ChatRoomView key={roomId} roomId={roomId} onRevoked={onRevoked} />
            </div>
        </div>
    );
}
