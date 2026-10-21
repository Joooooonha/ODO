package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.ChatMessageType;
import SeSAC.Dance_Assessment.Domain.ChatRoomType;
import SeSAC.Dance_Assessment.Dto.Chat.ChatMessageResponse;
import SeSAC.Dance_Assessment.Dto.Chat.ChatRoomSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 채팅 조회·DM 생성 SQL. PostgreSQL 전용 문법(ON CONFLICT, RETURNING)과 {@code timestamptz}를
 * 그대로 다루려고 JPA 대신 SQL로 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class ChatQueries {

    /**
     * 내 방 목록. 팀 방과 DM을 한 목록에 최근 순으로.
     *
     * <ul>
     *   <li>마지막 메시지는 {@code last_seq} 번호의 메시지다. 한 문장 안에서 읽으므로 방 번호와
     *       메시지가 어긋나지 않는다(번호와 메시지는 같은 트랜잭션에서 커밋된다)</li>
     *   <li>정렬은 시각 기준 — seq는 방마다 따로라 방끼리 비교할 수 없다. 메시지가 없는 방은
     *       방을 만든 시각</li>
     *   <li>메시지 없는 DM도 돌려주고 {@code empty}로 표시한다. <b>숨기는 것은 화면이다</b>(E1: 양쪽 목록에서
     *       숨김). 브라우저가 빈 DM의 존재를 알아야 구독할 수 있다 — 연결 직후 GRANTED를 놓쳐도 목록 조회가
     *       복구 경로가 된다(10/19)</li>
     * </ul>
     */
    private static final String MY_ROOMS = """
            SELECT r.room_id, r.type, r.team_id, r.last_seq, r.created_at AS room_created_at,
                   m.last_read_seq,
                   msg.message_id, msg.seq, msg.type AS msg_type, msg.sender_id, s.nickname AS sender_nickname,
                   msg.content, msg.client_message_id, msg.created_at, tu.nickname AS target_nickname,
                   CASE WHEN r.type = 'TEAM' THEN t.team_name ELSE u.nickname END AS display_name,
                   CASE WHEN r.type = 'DM' THEN u.user_id END AS peer_user_id,
                   (r.type = 'DM' AND r.last_seq = 0) AS empty
            FROM chat_room_member m
            JOIN chat_room r      ON r.room_id = m.room_id
            LEFT JOIN message msg ON msg.room_id = r.room_id AND msg.seq = r.last_seq
            LEFT JOIN users s     ON s.user_id = msg.sender_id
            LEFT JOIN users tu    ON tu.user_id = CASE WHEN msg.type = 'SYSTEM' THEN (msg.content::jsonb ->> 'targetUserId')::bigint END
            LEFT JOIN team t      ON r.type = 'TEAM' AND t.team_id = r.team_id
            LEFT JOIN users u     ON r.type = 'DM'
                                 AND u.user_id = CASE WHEN r.dm_user_low = ? THEN r.dm_user_high ELSE r.dm_user_low END
            WHERE m.user_id = ? AND m.status = 'ACTIVE'
            ORDER BY COALESCE(msg.created_at, r.created_at) DESC, r.room_id DESC
            """;

    /*
     * 시스템 메시지(입장·나가기·추방)는 사건만 저장한다({"event":"KICK","targetUserId":7}). 화면이 문장을
     * 만들 수 있게 대상의 "현재" 닉네임을 조회할 때 붙인다(target_nickname). 나간·추방된 사람은 팀 멤버
     * 목록에 없어 브라우저가 따로 알 방법이 없다.
     * JSON 변환은 CASE 안에 둔다 — PostgreSQL은 AND의 평가 순서를 보장하지 않아, 밖에 두면 일반 글의
     * content까지 JSON으로 읽으려다 실패할 수 있다. CASE는 필요한 분기만 평가한다.
     */
    private static final String MESSAGE_COLUMNS = """
            SELECT msg.message_id, msg.seq, msg.type AS msg_type, msg.sender_id, s.nickname AS sender_nickname,
                   msg.content, msg.client_message_id, msg.created_at, tu.nickname AS target_nickname
            FROM message msg
            LEFT JOIN users s  ON s.user_id = msg.sender_id
            LEFT JOIN users tu ON tu.user_id = CASE WHEN msg.type = 'SYSTEM' THEN (msg.content::jsonb ->> 'targetUserId')::bigint END
            WHERE msg.room_id = ?
            """;

    private final JdbcTemplate jdbc;

    public List<ChatRoomSummaryResponse> findMyRooms(long me) {
        return jdbc.query(MY_ROOMS, (rs, i) -> {
            long lastSeq = rs.getLong("last_seq");
            long myReadSeq = rs.getLong("last_read_seq");
            ChatMessageResponse last = rs.getObject("message_id") == null ? null : mapMessage(rs, me);
            return new ChatRoomSummaryResponse(
                    rs.getLong("room_id"),
                    ChatRoomType.valueOf(rs.getString("type")),
                    rs.getString("display_name"),
                    rs.getObject("team_id", Long.class),
                    rs.getObject("peer_user_id", Long.class),
                    lastSeq,
                    last,
                    myReadSeq,
                    lastSeq - myReadSeq,
                    rs.getBoolean("empty"));
        }, me, me);
    }

    /** 최신 {@code limit}개. 오름차순으로 돌려준다. */
    public List<ChatMessageResponse> findLatest(long roomId, int limit, long me) {
        return reversed(jdbc.query(MESSAGE_COLUMNS + " ORDER BY msg.seq DESC LIMIT ?",
                (rs, i) -> mapMessage(rs, me), roomId, limit));
    }

    /** {@code afterSeq}보다 큰 번호부터 {@code limit}개(오름차순). 재연결·빈틈 채우기. */
    public List<ChatMessageResponse> findAfter(long roomId, long afterSeq, int limit, long me) {
        return jdbc.query(MESSAGE_COLUMNS + " AND msg.seq > ? ORDER BY msg.seq ASC LIMIT ?",
                (rs, i) -> mapMessage(rs, me), roomId, afterSeq, limit);
    }

    /** {@code beforeSeq}보다 작은 번호 중 가장 가까운 {@code limit}개(오름차순). 위로 스크롤. */
    public List<ChatMessageResponse> findBefore(long roomId, long beforeSeq, int limit, long me) {
        return reversed(jdbc.query(MESSAGE_COLUMNS + " AND msg.seq < ? ORDER BY msg.seq DESC LIMIT ?",
                (rs, i) -> mapMessage(rs, me), roomId, beforeSeq, limit));
    }

    /** 두 사람이 지금 ACTIVE 멤버로 함께 속한 팀이 하나라도 있는가. */
    public boolean shareActiveTeam(long a, long b) {
        Boolean shared = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM team_member x
                    JOIN team_member y ON y.team_id = x.team_id
                    WHERE x.user_id = ? AND y.user_id = ? AND x.status = 'ACTIVE' AND y.status = 'ACTIVE')
                """, Boolean.class, a, b);
        return Boolean.TRUE.equals(shared);
    }

    public Optional<Long> findDmRoom(long low, long high) {
        return jdbc.query("SELECT room_id FROM chat_room WHERE dm_user_low = ? AND dm_user_high = ?",
                (rs, i) -> rs.getLong(1), low, high).stream().findFirst();
    }

    /**
     * DM 방을 만든다. 같은 두 사람이 동시에 만들면 늦은 쪽은 앞 트랜잭션이 끝날 때까지
     * {@code uk_chat_room_dm_pair}에서 기다렸다가 아무것도 하지 않는다(빈 결과).
     *
     * @return 새로 만든 방 id. 이미 있었으면 empty
     */
    public Optional<Long> insertDmRoom(long low, long high) {
        return jdbc.query("""
                INSERT INTO chat_room (type, dm_user_low, dm_user_high) VALUES ('DM', ?, ?)
                ON CONFLICT ON CONSTRAINT uk_chat_room_dm_pair DO NOTHING RETURNING room_id
                """, (rs, i) -> rs.getLong(1), low, high).stream().findFirst();
    }

    /** DM 멤버. DM에는 팀 멤버 변화가 영향을 주지 않으므로 항상 ACTIVE다. */
    public void insertDmMember(long roomId, long userId) {
        jdbc.update("""
                INSERT INTO chat_room_member (room_id, user_id, status, last_read_seq) VALUES (?, ?, 'ACTIVE', 0)
                ON CONFLICT ON CONSTRAINT uk_chat_room_member_room_user DO NOTHING
                """, roomId, userId);
    }

    private static ChatMessageResponse mapMessage(ResultSet rs, long me) throws SQLException {
        Long senderId = rs.getObject("sender_id", Long.class);
        UUID clientMessageId = rs.getObject("client_message_id", UUID.class);
        return new ChatMessageResponse(
                rs.getLong("message_id"),
                rs.getLong("seq"),
                ChatMessageType.valueOf(rs.getString("msg_type")),
                senderId,
                rs.getString("sender_nickname"),
                rs.getString("content"),
                // 남의 UUID는 싣지 않는다. 본인 탭이 재전송 판단에만 쓴다.
                senderId != null && senderId == me ? clientMessageId : null,
                instant(rs, "created_at"),
                rs.getString("target_nickname"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }
}
