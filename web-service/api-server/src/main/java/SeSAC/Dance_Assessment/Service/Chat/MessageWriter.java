package SeSAC.Dance_Assessment.Service.Chat;

import SeSAC.Dance_Assessment.Domain.ChatMessageType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 메시지 한 건의 저장 트랜잭션. <b>번호 부여와 저장만 한다</b> — 다른 서버로의 전달, 확정 응답은
 * 커밋 뒤에 한다.
 *
 * <pre>
 * ① UPDATE chat_room SET last_seq = last_seq + 1 ... RETURNING   방 행 잠금 + 번호 + 시각
 * ② 보낸 사람이 ACTIVE 멤버인지 (잠금 "뒤에" 읽는다)                아니면 롤백 → 번호도 되돌아감
 * ③ INSERT message ... ON CONFLICT (sender_id, client_message_id) DO NOTHING
 * </pre>
 *
 * <ul>
 *   <li>①이 읽기·증가·쓰기를 한 문장으로 하므로 끼어들 틈이 없다. 같은 방의 다음 저장은 ①에서
 *       앞 트랜잭션이 끝날 때까지 기다린다 → 번호 순서 = 커밋 순서, 롤백하면 번호도 되돌아가
 *       빈틈이 없다. 다른 방은 다른 행이라 병렬로 돈다</li>
 *   <li>②를 ① 뒤에 하는 이유: 나가기·추방도 이 방 행을 먼저 잠근다({@code ChatRoomService}).
 *       잠금을 얻은 뒤의 조회는 추방 결과를 반드시 본다 → 추방이 커밋된 순간부터 전송은 거부된다</li>
 *   <li>시각을 ①에서 받는 이유: {@code now()}는 트랜잭션 시작 시각이라 잠금을 기다리는 동안
 *       번호와 시각이 뒤집힐 수 있다. 잠금을 쥔 채 {@code clock_timestamp()}로 찍으면 번호가 크면
 *       시각도 같거나 늦다</li>
 *   <li>③에서 충돌하면 기존 행을 읽고 {@link DuplicateMessageException}으로 롤백한다(올린 번호를
 *       되돌리려고). 충돌 대상을 명시한다 — 비워 두면 안전망인 {@code uk_message_room_seq} 위반까지
 *       조용히 삼킨다</li>
 * </ul>
 *
 * <p><b>REQUIRES_NEW인 이유.</b> 시스템 메시지는 팀 트랜잭션의 커밋 직후(AFTER_COMMIT)에 저장하는데,
 * 그 시점에도 원래 트랜잭션의 자원이 아직 묶여 있어서 기본 전파로는 이미 끝난 트랜잭션에 합류해
 * <b>실제로 커밋되지 않는다</b>({@code TransactionalEventListener} javadoc). 웹소켓 경로에는 바깥
 * 트랜잭션이 없어 결과가 같다.
 *
 * <p><b>시간 제한 {@value #TIMEOUT_SECONDS}초는 트랜잭션 전체에 건다.</b> 각 문장은 남은 시간을
 * 쿼리 시간 제한으로 받는다(잠금 대기 포함). 넘으면 PostgreSQL이 문장을 취소(SQLState 57014)하고
 * 롤백된다 — "느림"이 아니라 이렇게 취소된 것만 재시도 대상이다. 커넥션을 얻는 시간은 여기에
 * 들어가지 않는다(커넥션을 얻은 뒤부터 잰다).
 *
 * <p>호출은 {@link ChatMessageService}만 한다. 재시도는 그쪽에서 이 메서드를 다시 부르는
 * 방식이라 <b>반드시 별도 빈</b>이어야 한다(자기 호출은 프록시를 거치지 않아 새 트랜잭션이
 * 생기지 않는다).
 */
@Component
@RequiredArgsConstructor
public class MessageWriter {

    static final int TIMEOUT_SECONDS = 2;

    private static final String NEXT_SEQ =
            "UPDATE chat_room SET last_seq = last_seq + 1, modified_at = clock_timestamp()"
                    + " WHERE room_id = ? RETURNING last_seq, modified_at";

    private static final String ACTIVE_SENDER =
            "SELECT u.nickname FROM chat_room_member m JOIN users u ON u.user_id = m.user_id"
                    + " WHERE m.room_id = ? AND m.user_id = ? AND m.status = 'ACTIVE'";

    private static final String INSERT_TEXT =
            "INSERT INTO message (room_id, seq, sender_id, type, content, client_message_id, created_at)"
                    + " VALUES (?, ?, ?, 'TEXT', ?, ?, ?)"
                    + " ON CONFLICT (sender_id, client_message_id) DO NOTHING RETURNING message_id";

    private static final String INSERT_SYSTEM =
            "INSERT INTO message (room_id, seq, sender_id, type, content, client_message_id, created_at)"
                    + " VALUES (?, ?, NULL, 'SYSTEM', ?, ?, ?)"
                    + " ON CONFLICT (client_message_id) WHERE type = 'SYSTEM' DO NOTHING RETURNING message_id";

    private static final String EXISTING_TEXT =
            "SELECT m.message_id, m.room_id, m.seq, m.sender_id, u.nickname, m.type, m.content,"
                    + " m.client_message_id, m.created_at"
                    + " FROM message m JOIN users u ON u.user_id = m.sender_id"
                    + " WHERE m.sender_id = ? AND m.client_message_id = ?";

    private static final String EXISTING_SYSTEM =
            "SELECT m.message_id, m.room_id, m.seq, m.sender_id, NULL AS nickname, m.type, m.content,"
                    + " m.client_message_id, m.created_at"
                    + " FROM message m WHERE m.type = 'SYSTEM' AND m.client_message_id = ?";

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    /**
     * 사람이 보낸 메시지. 입력 검증은 끝났다고 본다({@link ChatMessageService}).
     *
     * @throws NotChatMemberException   방이 없거나 보낸 사람이 ACTIVE 멤버가 아님(롤백)
     * @throws DuplicateMessageException 같은 메시지가 이미 있음(롤백, 기존 메시지를 담고 있다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TIMEOUT_SECONDS)
    public SavedMessage writeText(long roomId, long senderId, UUID clientMessageId, String content) {
        Seq seq = nextSeq(roomId);
        if (seq == null) {
            throw new NotChatMemberException(roomId, senderId);
        }
        List<String> nickname = jdbc.queryForList(ACTIVE_SENDER, String.class, roomId, senderId);
        if (nickname.isEmpty()) {
            throw new NotChatMemberException(roomId, senderId);
        }

        Long messageId = jdbc.query(INSERT_TEXT, MessageWriter::firstLongOrNull,
                roomId, seq.value(), senderId, content, clientMessageId, utc(seq.at()));
        if (messageId == null) {
            throw new DuplicateMessageException(jdbc.queryForObject(
                    EXISTING_TEXT, MessageWriter::mapSaved, senderId, clientMessageId));
        }
        return published(new SavedMessage(messageId, roomId, seq.value(), senderId, nickname.get(0),
                ChatMessageType.TEXT, content, clientMessageId, seq.at()));
    }

    /**
     * 입장·나가기·추방 안내. 멤버 확인을 하지 않는다(보낸 사람이 없다).
     * {@code eventId}는 사건이 생길 때 한 번 만든 UUID라, 같은 사건을 다시 저장해도 한 건이다.
     *
     * @throws DuplicateMessageException 같은 사건의 안내가 이미 있음(롤백)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TIMEOUT_SECONDS)
    public SavedMessage writeSystem(long roomId, UUID eventId, String content) {
        Seq seq = nextSeq(roomId);
        if (seq == null) {
            throw new IllegalStateException("채팅방이 없음: roomId=" + roomId);
        }
        Long messageId = jdbc.query(INSERT_SYSTEM, MessageWriter::firstLongOrNull,
                roomId, seq.value(), content, eventId, utc(seq.at()));
        if (messageId == null) {
            throw new DuplicateMessageException(jdbc.queryForObject(
                    EXISTING_SYSTEM, MessageWriter::mapSaved, eventId));
        }
        return published(new SavedMessage(messageId, roomId, seq.value(), null, null,
                ChatMessageType.SYSTEM, content, eventId, seq.at()));
    }

    /** 커밋 뒤에 받을 쪽(서버 간 방송)이 쓰도록 알린다. 롤백되면 전달되지 않는다. */
    private SavedMessage published(SavedMessage saved) {
        events.publishEvent(new MessageSavedEvent(saved));
        return saved;
    }

    private Seq nextSeq(long roomId) {
        List<Seq> rows = jdbc.query(NEXT_SEQ,
                (rs, i) -> new Seq(rs.getLong(1), rs.getObject(2, OffsetDateTime.class).toInstant()),
                roomId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Long firstLongOrNull(ResultSet rs) throws SQLException {
        return rs.next() ? rs.getLong(1) : null;
    }

    static SavedMessage mapSaved(ResultSet rs, int rowNum) throws SQLException {
        return new SavedMessage(
                rs.getLong("message_id"),
                rs.getLong("room_id"),
                rs.getLong("seq"),
                rs.getObject("sender_id", Long.class),
                rs.getString("nickname"),
                ChatMessageType.valueOf(rs.getString("type")),
                rs.getString("content"),
                rs.getObject("client_message_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    private record Seq(long value, Instant at) {
    }
}
