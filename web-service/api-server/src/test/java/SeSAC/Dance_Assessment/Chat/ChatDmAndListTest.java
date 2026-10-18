package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.Service.Chat.SavedMessage;
import SeSAC.Dance_Assessment.TestChat;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DM 생성(7.3), 방 목록·메시지 조회 API(8절). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatDmAndListTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    ChatMessageService chatMessageService;

    @Autowired
    JdbcTemplate jdbc;

    TestUsers users;
    TestTeams teams;
    TestChat chat;

    @BeforeEach
    void setUp() {
        users = new TestUsers(mvc, om);
        teams = new TestTeams(mvc, om);
        chat = new TestChat(mvc, om, chatMessageService, jdbc);
    }

    // ===== DM =====

    @Test
    @DisplayName("같은 팀이면 DM이 생기고(멤버 2명), 다시 열면 같은 방이 돌아온다(created=false). 상대가 열어도 같은 방")
    void sameTeamOpensDm() throws Exception {
        LoggedIn a = users.create("가");
        LoggedIn b = users.create("나");
        teams.teamOf(a, b);

        JsonNode first = json(openDm(a, b.id()).andExpect(status().isOk()));
        assertThat(first.get("created").asBoolean()).isTrue();
        long roomId = first.get("roomId").asLong();

        assertThat(json(openDm(a, b.id())).get("created").asBoolean()).isFalse();
        assertThat(json(openDm(b, a.id())).get("roomId").asLong()).isEqualTo(roomId);
        assertThat(chat.member(roomId, a.id())).containsExactly("ACTIVE", 0L);
        assertThat(chat.member(roomId, b.id())).containsExactly("ACTIVE", 0L);
        assertThat(jdbc.queryForMap("SELECT type, dm_user_low, dm_user_high FROM chat_room WHERE room_id = ?", roomId))
                .containsEntry("type", "DM")
                .containsEntry("dm_user_low", Math.min(a.id(), b.id()))
                .containsEntry("dm_user_high", Math.max(a.id(), b.id()));
    }

    @Test
    @DisplayName("같은 팀이 아니고 DM 이력도 없으면 403 DM_NOT_ALLOWED, 없는 사용자도 403, 자기 자신은 400 CANNOT_DM_SELF")
    void dmRestrictions() throws Exception {
        LoggedIn a = users.create("가");
        LoggedIn stranger = users.create("남");
        teams.createTeam(a, "혼자 팀");
        teams.createTeam(stranger, "남의 팀");

        openDm(a, stranger.id()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DM_NOT_ALLOWED"));
        openDm(a, 999_999_999L).andExpect(status().isForbidden());
        openDm(a, a.id()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_DM_SELF"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_room WHERE type = 'DM' AND ? IN (dm_user_low, dm_user_high)", Long.class, a.id()))
                .isZero();
    }

    @Test
    @DisplayName("[spec 10/15] 팀을 나가거나 추방돼도 이미 있는 DM은 다시 열리고 계속 주고받는다")
    void dmSurvivesLeavingTeam() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn left = users.create("나간 사람");
        LoggedIn kicked = users.create("추방된 사람");
        Long teamId = teams.teamOf(leader, left, kicked);
        long dmLeft = chat.openDm(leader, left);
        long dmKicked = chat.openDm(kicked, leader);

        teams.leave(left, teamId);
        teams.kick(leader, teamId, kicked);

        assertThat(chat.openDm(left, leader)).as("이력이 있으면 기존 방").isEqualTo(dmLeft);
        assertThat(chat.openDm(kicked, leader)).isEqualTo(dmKicked);
        chat.send(left, dmLeft, "팀은 나갔지만");
        chat.send(leader, dmLeft, "DM은 계속");
        chat.send(kicked, dmKicked, "추방됐지만 DM은 된다");
        assertThat(chat.member(dmKicked, kicked.id())[0]).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("두 사람이 여러 팀에 함께 있어도 DM 방은 하나")
    void oneDmAcrossTeams() throws Exception {
        LoggedIn a = users.create("가");
        LoggedIn b = users.create("나");
        teams.teamOf(a, b);
        teams.teamOf(b, a);

        long first = chat.openDm(a, b);
        assertThat(chat.openDm(b, a)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_room WHERE dm_user_low = ? AND dm_user_high = ?",
                Long.class, Math.min(a.id(), b.id()), Math.max(a.id(), b.id()))).isEqualTo(1);
    }

    // ===== 방 목록 =====

    @Test
    @DisplayName("방 목록: 팀 방·DM이 한 목록에 최근 메시지 순, DM 이름은 상대 닉네임, unread = lastSeq − myReadSeq, 마지막 메시지는 last_seq의 메시지")
    void roomList() throws Exception {
        LoggedIn me = users.create("나");
        LoggedIn friend = users.create("친구");
        Long teamId = teams.teamOf(me, friend);
        long teamRoom = chat.teamRoomId(me, teamId);
        long dm = chat.openDm(me, friend);

        chat.send(friend, dm, "DM 먼저");
        SavedMessage last = chat.send(friend, teamRoom, "팀 방이 더 최근");

        JsonNode rooms = json(mvc.perform(get("/api/chat/rooms").cookie(me.cookie())).andExpect(status().isOk()));
        List<Long> order = new ArrayList<>();
        rooms.forEach(r -> order.add(r.get("roomId").asLong()));
        assertThat(order).containsSubsequence(teamRoom, dm);

        JsonNode team = find(rooms, teamRoom);
        assertThat(team.get("type").asText()).isEqualTo("TEAM");
        assertThat(team.get("displayName").asText()).isEqualTo("팀-나");
        assertThat(team.get("teamId").asLong()).isEqualTo(teamId);
        assertThat(team.get("lastSeq").asLong()).isEqualTo(last.seq());
        assertThat(team.get("lastMessage").get("seq").asLong()).isEqualTo(last.seq());
        assertThat(team.get("lastMessage").get("content").asText()).isEqualTo("팀 방이 더 최근");
        assertThat(team.get("lastMessage").get("senderNickname").asText()).isEqualTo("친구");
        assertThat(team.get("unread").asLong())
                .isEqualTo(team.get("lastSeq").asLong() - team.get("myReadSeq").asLong());

        JsonNode dmRow = find(rooms, dm);
        assertThat(dmRow.get("type").asText()).isEqualTo("DM");
        assertThat(dmRow.get("displayName").asText()).isEqualTo("친구");
        assertThat(dmRow.get("peerUserId").asLong()).isEqualTo(friend.id());
        assertThat(dmRow.get("unread").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("[E1·10/19] 메시지 없는 DM은 양쪽 목록에 empty: true로 온다(화면이 숨긴다, 구독은 한다). 첫 메시지 뒤에는 empty: false")
    void emptyDmIsFlagged() throws Exception {
        LoggedIn a = users.create("가");
        LoggedIn b = users.create("나");
        Long teamId = teams.teamOf(a, b);
        long dm = chat.openDm(a, b);
        long teamRoom = chat.teamRoomId(a, teamId);

        assertThat(emptyFlag(a, dm)).isTrue();
        assertThat(emptyFlag(b, dm)).isTrue();
        assertThat(emptyFlag(a, teamRoom)).as("팀 방은 메시지가 없어도 empty가 아니다").isFalse();

        chat.send(b, dm, "첫 메시지");
        assertThat(emptyFlag(a, dm)).isFalse();
        assertThat(emptyFlag(b, dm)).isFalse();
    }

    private boolean emptyFlag(LoggedIn user, long roomId) throws Exception {
        JsonNode rooms = json(mvc.perform(get("/api/chat/rooms").cookie(user.cookie())).andExpect(status().isOk()));
        return find(rooms, roomId).get("empty").asBoolean();
    }

    @Test
    @DisplayName("나가거나 추방된 팀의 방은 목록에서 빠진다")
    void inactiveRoomsLeaveList() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = teams.teamOf(leader, member);
        long room = chat.teamRoomId(leader, teamId);

        assertThat(roomIds(member)).contains(room);
        teams.leave(member, teamId);
        assertThat(roomIds(member)).doesNotContain(room);
    }

    // ===== 메시지 조회 =====

    @Test
    @DisplayName("메시지 조회: 최신 50 / afterSeq / beforeSeq가 정확하고 항상 오름차순, lastSeq를 함께 준다")
    void messagePaging() throws Exception {
        LoggedIn me = users.create("나");
        Long teamId = teams.createTeam(me, "기록 팀");
        long room = chat.teamRoomId(me, teamId);
        for (int i = 1; i <= 120; i++) {
            chat.send(me, room, "메시지 " + i);
        }

        assertThat(seqs(messages(me, room, ""))).containsExactlyElementsOf(range(71, 120));
        assertThat(seqs(messages(me, room, "?afterSeq=100"))).containsExactlyElementsOf(range(101, 120));
        assertThat(seqs(messages(me, room, "?beforeSeq=71"))).containsExactlyElementsOf(range(21, 70));
        assertThat(seqs(messages(me, room, "?beforeSeq=11&limit=5"))).containsExactlyElementsOf(range(6, 10));
        assertThat(seqs(messages(me, room, "?afterSeq=120"))).isEmpty();
        assertThat(messages(me, room, "").get("lastSeq").asLong()).isEqualTo(120);
    }

    @Test
    @DisplayName("메시지 조회: limit 범위 밖·afterSeq와 beforeSeq 동시 지정은 400, 멤버가 아니면(방이 없어도) 403, 로그인 안 하면 401")
    void messageAccess() throws Exception {
        LoggedIn me = users.create("나");
        LoggedIn outsider = users.create("밖");
        long room = chat.teamRoomId(me, teams.createTeam(me, "닫힌 팀"));

        mvc.perform(get("/api/chat/rooms/{id}/messages?limit=51", room).cookie(me.cookie()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat/rooms/{id}/messages?limit=0", room).cookie(me.cookie()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat/rooms/{id}/messages?afterSeq=1&beforeSeq=5", room).cookie(me.cookie()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat/rooms/{id}/messages", room).cookie(outsider.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_CHAT_MEMBER"));
        mvc.perform(get("/api/chat/rooms/{id}/messages", 987_654_321L).cookie(me.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_CHAT_MEMBER"));

        mvc.perform(get("/api/chat/rooms")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chat/rooms/{id}/messages", room)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chat/dms").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("본인이 보낸 메시지에만 clientMessageId가 실린다 (새로 고침 후 보낼 목록 정리용)")
    void clientMessageIdOnlyForOwnMessages() throws Exception {
        LoggedIn me = users.create("나");
        LoggedIn other = users.create("남");
        Long teamId = teams.teamOf(me, other);
        long room = chat.teamRoomId(me, teamId);
        SavedMessage mine = chat.send(me, room, "내 것");
        chat.send(other, room, "남의 것");

        JsonNode list = messages(me, room, "").get("messages");
        for (JsonNode m : list) {
            if ("TEXT".equals(m.get("type").asText()) && m.get("senderId").asLong() == me.id()) {
                assertThat(m.get("clientMessageId").asText()).isEqualTo(mine.clientMessageId().toString());
            } else {
                assertThat(m.get("clientMessageId").isNull()).as("남의 메시지·안내에는 없다: %s", m).isTrue();
            }
        }
        assertThat(list.get(list.size() - 1).get("createdAt").asText()).as("UTC 순간(Z)").endsWith("Z");
    }

    // ===== 도우미 =====

    private ResultActions openDm(LoggedIn me, Long peerId) throws Exception {
        return mvc.perform(post("/api/chat/dms").cookie(me.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("userId", peerId))));
    }

    private List<Long> roomIds(LoggedIn user) throws Exception {
        List<Long> ids = new ArrayList<>();
        json(mvc.perform(get("/api/chat/rooms").cookie(user.cookie())).andExpect(status().isOk()))
                .forEach(r -> ids.add(r.get("roomId").asLong()));
        return ids;
    }

    private JsonNode messages(LoggedIn user, long room, String query) throws Exception {
        return json(mvc.perform(get("/api/chat/rooms/" + room + "/messages" + query).cookie(user.cookie()))
                .andExpect(status().isOk()));
    }

    private static List<Long> seqs(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("messages").forEach(m -> out.add(m.get("seq").asLong()));
        return out;
    }

    private static List<Long> range(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    private static JsonNode find(JsonNode rooms, long roomId) {
        for (JsonNode r : rooms) {
            if (r.get("roomId").asLong() == roomId) {
                return r;
            }
        }
        throw new AssertionError("목록에 방이 없음: " + roomId);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return om.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
