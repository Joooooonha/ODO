package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.Service.Chat.ChatSendError;
import SeSAC.Dance_Assessment.Service.Chat.SavedMessage;
import SeSAC.Dance_Assessment.Service.Chat.SendOutcome;
import SeSAC.Dance_Assessment.TestChat;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 팀 채팅방과 팀 멤버 변화의 연결(7.1·7.2), 입장·나가기·추방 안내(E2).
 *
 * <p>트랜잭션을 테스트에 걸지 않는다 — 각 요청이 실제로 커밋돼야 안내 메시지(커밋 후 저장)도 생긴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatTeamRoomTest {

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

    @Test
    @DisplayName("팀을 만들면 팀 채팅방 1개와 팀장의 채팅 멤버(ACTIVE, 읽은 위치 0)가 함께 생기고, Team Space 응답에 방 id가 실린다")
    void createTeamCreatesRoom() throws Exception {
        LoggedIn leader = users.create("팀장");
        Long teamId = teams.createTeam(leader, "채팅 팀");

        long roomId = chat.teamRoomId(leader, teamId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_room WHERE team_id = ?", Long.class, teamId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM chat_room WHERE room_id = ?", String.class, roomId))
                .isEqualTo("TEAM");
        assertThat(chat.member(roomId, leader.id())).containsExactly("ACTIVE", 0L);
        assertThat(chat.lastSeq(roomId)).isZero();
    }

    @Test
    @DisplayName("참여·나가기·재활성화·추방이 채팅 멤버 상태에 반영되고, 재가입자의 읽은 위치는 재가입 시점의 last_seq다")
    void membershipFollowsTeam() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = teams.teamOf(leader, member);
        long roomId = chat.teamRoomId(leader, teamId);
        String code = teams.joinCode(leader, teamId);

        // 참여: 잠금 아래에서 읽은 last_seq(0)가 읽은 위치. 그 뒤에 입장 안내가 1번을 받는다.
        assertThat(chat.member(roomId, member.id())).containsExactly("ACTIVE", 0L);

        chat.send(leader, roomId, "하나");
        chat.send(leader, roomId, "둘");
        teams.leave(member, teamId);
        assertThat(chat.member(roomId, member.id())[0]).isEqualTo("LEFT");

        chat.send(leader, roomId, "셋");
        long beforeRejoin = chat.lastSeq(roomId);
        teams.rejoin(member, code);
        assertThat(chat.member(roomId, member.id()))
                .as("예전 읽은 위치를 되살리지 않고 재가입 시점의 last_seq")
                .containsExactly("ACTIVE", beforeRejoin);
        assertThat(chat.lastSeq(roomId)).as("재가입 안내가 다음 번호를 받는다").isEqualTo(beforeRejoin + 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_room_member WHERE room_id = ? AND user_id = ?", Long.class, roomId, member.id()))
                .as("재가입은 새 행이 아니라 기존 행을 다시 쓴다").isEqualTo(1);

        teams.kick(leader, teamId, member);
        assertThat(chat.member(roomId, member.id())[0]).isEqualTo("KICKED");
        chat.assertContiguous(roomId);
    }

    @Test
    @DisplayName("상태가 바뀔 때마다 modified_at이 갱신된다 (5초 재확인이 이 칸으로 변경을 찾는다)")
    void modifiedAtMovesOnEveryChange() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = teams.teamOf(leader, member);
        long roomId = chat.teamRoomId(leader, teamId);

        java.time.OffsetDateTime joined = modifiedAt(roomId, member.id());
        teams.leave(member, teamId);
        java.time.OffsetDateTime left = modifiedAt(roomId, member.id());
        teams.rejoin(member, teams.joinCode(leader, teamId));
        java.time.OffsetDateTime rejoined = modifiedAt(roomId, member.id());
        teams.kick(leader, teamId, member);
        java.time.OffsetDateTime kicked = modifiedAt(roomId, member.id());

        assertThat(left).isAfter(joined);
        assertThat(rejoined).isAfter(left);
        assertThat(kicked).isAfter(rejoined);
    }

    @Test
    @DisplayName("[E2] 참여·나가기·재활성화·추방마다 팀 채팅방에 안내 1건 — 보낸 사람 없음, 내용은 사건")
    void systemMessagePerEvent() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = teams.teamOf(leader, member);
        long roomId = chat.teamRoomId(leader, teamId);

        teams.leave(member, teamId);
        teams.rejoin(member, teams.joinCode(leader, teamId));
        teams.kick(leader, teamId, member);

        long id = member.id();
        assertThat(chat.systemContents(roomId)).containsExactly(
                "{\"event\":\"JOIN\",\"targetUserId\":" + id + "}",
                "{\"event\":\"LEAVE\",\"targetUserId\":" + id + "}",
                "{\"event\":\"JOIN\",\"targetUserId\":" + id + "}",
                "{\"event\":\"KICK\",\"targetUserId\":" + id + "}");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM message WHERE room_id = ? AND type = 'SYSTEM' AND sender_id IS NOT NULL",
                Long.class, roomId)).isZero();
        chat.assertContiguous(roomId);
    }

    @Test
    @DisplayName("[E2·D4] 추방 안내는 추방된 사람의 마지막 메시지보다 뒤 번호이고, 추방 뒤 전송은 NOT_MEMBER로 거부되며 번호도 쓰지 않는다")
    void kickOrdersAndBlocks() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = teams.teamOf(leader, member);
        long roomId = chat.teamRoomId(leader, teamId);

        chat.send(member, roomId, "추방 전 1");
        chat.send(member, roomId, "추방 전 2");
        teams.kick(leader, teamId, member);

        assertThat(chat.systemSeq(roomId, "KICK", member.id()))
                .isGreaterThan(chat.maxTextSeq(roomId, member.id()));

        long seqBefore = chat.lastSeq(roomId);
        long countBefore = chat.messageCount(roomId);
        SendOutcome outcome = chat.trySend(member, roomId, UUID.randomUUID(), "추방 뒤");
        assertThat(outcome).isEqualTo(new SendOutcome.Rejected(ChatSendError.NOT_MEMBER));
        assertThat(chat.lastSeq(roomId)).as("번호를 소비하지 않는다(롤백)").isEqualTo(seqBefore);
        assertThat(chat.messageCount(roomId)).as("행도 없다").isEqualTo(countBefore);
    }

    @Test
    @DisplayName("스스로 나간 사람도 보낼 수 없다(NOT_MEMBER). 다시 참여하면 보낼 수 있다")
    void leftMemberCannotSend() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = teams.teamOf(leader, member);
        long roomId = chat.teamRoomId(leader, teamId);

        teams.leave(member, teamId);
        assertThat(chat.trySend(member, roomId, UUID.randomUUID(), "나간 뒤"))
                .isEqualTo(new SendOutcome.Rejected(ChatSendError.NOT_MEMBER));

        teams.rejoin(member, teams.joinCode(leader, teamId));
        SavedMessage saved = chat.send(member, roomId, "돌아왔다");
        assertThat(saved.seq()).isEqualTo(chat.lastSeq(roomId));
    }

    @Test
    @DisplayName("Team Space 응답(GET /api/teams/{id})에 chatRoomId가 실린다. 목록 응답에는 없다")
    void teamDetailHasChatRoomId() throws Exception {
        LoggedIn leader = users.create("팀장");
        Long teamId = teams.createTeam(leader, "방 id 팀");
        long roomId = jdbc.queryForObject("SELECT room_id FROM chat_room WHERE team_id = ?", Long.class, teamId);

        mvc.perform(get("/api/teams/{id}", teamId).cookie(leader.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRoomId").value(roomId))
                .andExpect(jsonPath("$.name").value("방 id 팀"));
        mvc.perform(get("/api/teams").cookie(leader.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chatRoomId").doesNotExist());
    }

    private java.time.OffsetDateTime modifiedAt(long roomId, long userId) {
        return jdbc.queryForObject(
                "SELECT modified_at FROM chat_room_member WHERE room_id = ? AND user_id = ?",
                java.time.OffsetDateTime.class, roomId, userId);
    }
}
