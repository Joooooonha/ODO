package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Service.Chat.ChatSendError;

import java.time.Instant;
import java.util.UUID;

/** 개인 목적지로 보내는 프레임 본문. {@code type}으로 구분한다. */
public final class ChatFrames {

    private ChatFrames() {
    }

    /** 확정. {@code duplicate}면 이미 저장돼 있던 메시지의 번호다. */
    public record Ack(String type, UUID clientMessageId, long roomId, long seq, Instant createdAt, boolean duplicate) {
        public Ack(UUID clientMessageId, long roomId, long seq, Instant createdAt, boolean duplicate) {
            this("ACK", clientMessageId, roomId, seq, createdAt, duplicate);
        }
    }

    /** 전송 오류. {@code clientMessageId}는 본문을 읽지 못했으면 null이다. */
    public record Error(String type, String clientMessageId, long roomId, ChatSendError code) {
        public Error(String clientMessageId, long roomId, ChatSendError code) {
            this("ERROR", clientMessageId, roomId, code);
        }
    }

    /** 구독 장벽 응답({@code /app/sync}). */
    public record Sync(String type) {
    }

    public static final Sync SYNC = new Sync("SYNC");

    /** 방 접근이 끊김(REVOKED)·생김(GRANTED). */
    public record Access(String type, long roomId) {
        public static Access revoked(long roomId) {
            return new Access("REVOKED", roomId);
        }

        public static Access granted(long roomId) {
            return new Access("GRANTED", roomId);
        }
    }
}
