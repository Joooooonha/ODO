package SeSAC.Dance_Assessment;

import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.Service.Chat.SavedMessage;
import SeSAC.Dance_Assessment.Service.Chat.SendOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅 테스트의 공통 준비와 DB 확인.
 *
 * <p>메시지 전송은 웹소켓이 아니라 {@link ChatMessageService}를 직접 부른다(STOMP는 실시간 전달
 * 단계에서 붙는다). 저장 경로는 같다.
 */
public final class TestChat {

    private final MockMvc mvc;
    private final ObjectMapper om;
    private final ChatMessageService chatMessageService;
    private final JdbcTemplate jdbc;

    public TestChat(MockMvc mvc, ObjectMapper om, ChatMessageService chatMessageService, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.om = om;
        this.chatMessageService = chatMessageService;
        this.jdbc = jdbc;
    }

    /** Team Space 응답의 팀 채팅방 id. */
    public long teamRoomId(LoggedIn member, Long teamId) throws Exception {
        String body = mvc.perform(get("/api/teams/{id}", teamId).cookie(member.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("chatRoomId").asLong();
    }

    public long openDm(LoggedIn me, LoggedIn peer) throws Exception {
        String body = mvc.perform(post("/api/chat/dms").cookie(me.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("userId", peer.id()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("roomId").asLong();
    }

    public SendOutcome trySend(LoggedIn sender, long roomId, UUID clientMessageId, String content) {
        return chatMessageService.sendText(sender.id(), roomId, clientMessageId.toString(), content);
    }

    /** 저장돼야 하는 메시지. 저장되지 않으면 테스트가 실패한다. */
    public SavedMessage send(LoggedIn sender, long roomId, String content) {
        SendOutcome outcome = trySend(sender, roomId, UUID.randomUUID(), content);
        assertThat(outcome).as("저장돼야 한다: %s", content).isInstanceOf(SendOutcome.Saved.class);
        return ((SendOutcome.Saved) outcome).message();
    }

    public long lastSeq(long roomId) {
        return jdbc.queryForObject("SELECT last_seq FROM chat_room WHERE room_id = ?", Long.class, roomId);
    }

    public long messageCount(long roomId) {
        return jdbc.queryForObject("SELECT count(*) FROM message WHERE room_id = ?", Long.class, roomId);
    }

    /** {@code [status, last_read_seq]}. 행이 없으면 null. */
    public Object[] member(long roomId, long userId) {
        List<Object[]> rows = jdbc.query(
                "SELECT status, last_read_seq FROM chat_room_member WHERE room_id = ? AND user_id = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getLong(2)}, roomId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 방 안 번호가 1부터 빈틈·중복 없이 {@code last_seq}까지 이어지는지. */
    public void assertContiguous(long roomId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT count(*) AS n, count(DISTINCT seq) AS d, coalesce(min(seq), 0) AS lo, coalesce(max(seq), 0) AS hi"
                        + " FROM message WHERE room_id = ?", roomId);
        long n = ((Number) row.get("n")).longValue();
        assertThat(((Number) row.get("d")).longValue()).as("중복 번호 없음").isEqualTo(n);
        assertThat(n == 0 || ((Number) row.get("lo")).longValue() == 1).as("1부터 시작").isTrue();
        assertThat(((Number) row.get("hi")).longValue()).as("빈틈 없음(max = count)").isEqualTo(n);
        assertThat(lastSeq(roomId)).as("chat_room.last_seq와 일치").isEqualTo(n);
    }

    /** 11.4의 전역 불변식 쿼리. 모든 방에서 0건이어야 한다. */
    public List<Long> roomsWithGaps() {
        return jdbc.queryForList(
                "SELECT room_id FROM message GROUP BY room_id HAVING max(seq) <> count(*)", Long.class);
    }

    /** 방의 시스템 메시지 내용들(번호 순). */
    public List<String> systemContents(long roomId) {
        return jdbc.queryForList(
                "SELECT content FROM message WHERE room_id = ? AND type = 'SYSTEM' ORDER BY seq", String.class, roomId);
    }

    public long systemSeq(long roomId, String event, long targetUserId) {
        return jdbc.queryForObject(
                "SELECT seq FROM message WHERE room_id = ? AND type = 'SYSTEM'"
                        + " AND content = ?", Long.class, roomId,
                "{\"event\":\"" + event + "\",\"targetUserId\":" + targetUserId + "}");
    }

    /** 그 사람이 그 방에 보낸 TEXT 중 가장 큰 번호. 없으면 0. */
    public long maxTextSeq(long roomId, long senderId) {
        return jdbc.queryForObject(
                "SELECT coalesce(max(seq), 0) FROM message WHERE room_id = ? AND sender_id = ? AND type = 'TEXT'",
                Long.class, roomId, senderId);
    }
}
