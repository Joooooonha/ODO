package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.RawStompClient;
import SeSAC.Dance_Assessment.Realtime.ChatConnection;
import SeSAC.Dance_Assessment.Realtime.ChatConnectionRegistry;
import SeSAC.Dance_Assessment.Realtime.ChatDestinations;
import SeSAC.Dance_Assessment.Realtime.ChatRealtimeMetrics;
import SeSAC.Dance_Assessment.Realtime.RoomSubscriptions;
import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.TestChat;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 권한과 차단(6절) — 한 서버 안. 서버 2대에서의 차단은 {@link ChatTwoServerTest}, 제어 이벤트 유실은
 * {@link ChatAccessRecheckTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatAccessTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    ChatMessageService chatMessageService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ChatConnectionRegistry registry;

    @Autowired
    RoomSubscriptions roomSubscriptions;

    @Autowired
    ChatRealtimeMetrics metrics;

    TestUsers users;
    TestTeams teams;
    TestChat chat;
    final List<RawStompClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        users = new TestUsers(mvc, om);
        teams = new TestTeams(mvc, om);
        chat = new TestChat(mvc, om, chatMessageService, jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (RawStompClient c : clients) {
            c.close();
        }
    }

    private RawStompClient connect(LoggedIn user, long... rooms) throws Exception {
        RawStompClient c = RawStompClient.connect(port, user.cookie());
        clients.add(c);
        for (long room : rooms) {
            c.subscribe(ChatDestinations.roomTopic(room));
        }
        c.awaitSubscriptionsProcessed();
        return c;
    }

    private void awaitRoomSessions(long room, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (roomSubscriptions.sessions(room).size() != n) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("방 " + room + " 구독 세션 " + roomSubscriptions.sessions(room).size() + " ≠ " + n);
            }
            Thread.sleep(20);
        }
    }

    private static Predicate<RawStompClient.Frame> roomMessage(long room) {
        return f -> "MESSAGE".equals(f.command()) && ChatDestinations.roomTopic(room).equals(f.destination());
    }

    @Test
    @DisplayName("[6.1] CONNECT 때 DB의 ACTIVE 방 목록으로 허용 방 캐시를 채운다(나간 팀 방은 없다)")
    void connectLoadsAllowedRooms() throws Exception {
        LoggedIn me = users.create("나");
        LoggedIn other = users.create("남");
        long mine = chat.teamRoomId(me, teams.createTeam(me, "내 팀"));
        Long leftTeam = teams.teamOf(other, me);
        long leftRoom = chat.teamRoomId(other, leftTeam);
        teams.leave(me, leftTeam);

        connect(me);
        ChatConnection connection = registry.ofUser(me.id()).get(0);
        assertThat(connection.allowedRooms()).contains(mine).doesNotContain(leftRoom);
    }

    @Test
    @DisplayName("[6.3·D3] 추방되면 그 세션에 REVOKED, 웹소켓은 유지, 그 방 메시지(추방 안내 포함)는 더 받지 않고 다른 방은 계속 받는다")
    void kickRevokesOnlyThatRoom() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = teams.teamOf(leader, member);
        long teamRoom = chat.teamRoomId(leader, teamId);
        long dm = chat.openDm(leader, member);
        RawStompClient leaderWs = connect(leader, teamRoom);
        RawStompClient memberWs = connect(member, teamRoom, dm);
        awaitRoomSessions(teamRoom, 2);
        awaitRoomSessions(dm, 1);

        teams.kick(leader, teamId, member);

        JsonNode event = memberWs.nextEvent(WAIT);
        assertThat(event.get("type").asText()).isEqualTo("REVOKED");
        assertThat(event.get("roomId").asLong()).isEqualTo(teamRoom);
        JsonNode kickNotice = leaderWs.nextRoomMessage(teamRoom, WAIT);
        assertThat(kickNotice.get("type").asText()).as("팀장은 추방 안내를 받는다").isEqualTo("SYSTEM");
        assertThat(kickNotice.get("content").asText()).contains("KICK");

        chat.send(leader, teamRoom, "추방 뒤 팀 방");
        chat.send(leader, dm, "DM은 계속");
        assertThat(memberWs.nextRoomMessage(dm, WAIT).get("content").asText()).isEqualTo("DM은 계속");
        memberWs.expectNone(roomMessage(teamRoom), Duration.ofSeconds(1));
        assertThat(memberWs.isOpen()).isTrue();
        assertThat(roomSubscriptions.sessions(teamRoom)).as("방 구독표에서도 빠졌다").hasSize(1);
        assertThat(registry.ofUser(member.id()).get(0).isAllowed(teamRoom)).isFalse();

        // 다시 구독해도 거부된다(캐시에 없고 DB도 KICKED)
        memberWs.subscribe(ChatDestinations.roomTopic(teamRoom));
        assertThat(memberWs.nextEvent(WAIT).get("type").asText()).isEqualTo("REVOKED");
        chat.send(leader, teamRoom, "다시 구독해도 안 보인다");
        memberWs.expectNone(roomMessage(teamRoom), Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("[6.3] 스스로 나가도 같은 세션의 그 방이 끊긴다. 다시 참여하면 GRANTED → 재연결 없이 구독하고 받는다")
    void leaveThenRejoinWithoutReconnect() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = teams.teamOf(leader, member);
        long room = chat.teamRoomId(leader, teamId);
        RawStompClient memberWs = connect(member, room);
        awaitRoomSessions(room, 1);

        teams.leave(member, teamId);
        assertThat(memberWs.nextEvent(WAIT).get("type").asText()).isEqualTo("REVOKED");
        awaitRoomSessions(room, 0);

        teams.rejoin(member, teams.joinCode(leader, teamId));
        JsonNode granted = memberWs.nextEvent(WAIT);
        assertThat(granted.get("type").asText()).isEqualTo("GRANTED");
        assertThat(granted.get("roomId").asLong()).isEqualTo(room);

        memberWs.subscribe(ChatDestinations.roomTopic(room));
        memberWs.awaitSubscriptionsProcessed();
        awaitRoomSessions(room, 1);
        chat.send(leader, room, "돌아온 걸 환영");
        assertThat(memberWs.nextRoomMessage(room, WAIT).get("content").asText()).isEqualTo("돌아온 걸 환영");
    }

    @Test
    @DisplayName("[E1·7.3] DM을 만들면 접속 중인 상대에게 GRANTED가 가고, 상대는 구독해 첫 메시지를 실시간으로 받는다")
    void dmGrantReachesPeer() throws Exception {
        LoggedIn a = users.create("가");
        LoggedIn b = users.create("나");
        teams.teamOf(a, b);
        RawStompClient bWs = connect(b);
        RawStompClient aWs = connect(a);

        long dm = chat.openDm(a, b);
        JsonNode granted = bWs.nextEvent(WAIT);
        assertThat(granted.get("type").asText()).isEqualTo("GRANTED");
        assertThat(granted.get("roomId").asLong()).isEqualTo(dm);

        bWs.subscribe(ChatDestinations.roomTopic(dm));
        aWs.subscribe(ChatDestinations.roomTopic(dm));   // 만든 사람: GRANTED보다 먼저 와도 DB로 확인돼 통과
        bWs.awaitSubscriptionsProcessed();
        aWs.awaitSubscriptionsProcessed();
        awaitRoomSessions(dm, 2);
        aWs.sendMessage(dm, UUID.randomUUID().toString(), "첫 DM", false);
        assertThat(bWs.nextRoomMessage(dm, WAIT).get("content").asText()).isEqualTo("첫 DM");
    }

    @Test
    @DisplayName("[6.3] 청소(구독 해제)가 안 돼도 허용 방 캐시에서 빠지면 나가기 직전 검사가 막는다")
    void outboundCheckBlocksWithoutCleanup() throws Exception {
        LoggedIn me = users.create("나");
        long room = chat.teamRoomId(me, teams.createTeam(me, "캐시 팀"));
        RawStompClient ws = connect(me, room);
        awaitRoomSessions(room, 1);
        long droppedBefore = metrics.outboundDroppedCount();

        // 구독은 그대로 둔 채 캐시만 고친다 (청소 실패를 흉내)
        ChatConnection connection = registry.ofUser(me.id()).get(0);
        var allowed = ChatConnection.class.getDeclaredField("allowedRooms");
        allowed.setAccessible(true);
        ((java.util.Set<?>) allowed.get(connection)).remove(room);

        chat.send(me, room, "보이면 안 된다");
        ws.expectNone(roomMessage(room), Duration.ofSeconds(1));
        assertThat(roomSubscriptions.sessions(room)).as("구독표에는 남아 있다").hasSize(1);
        assertThat(metrics.outboundDroppedCount() - droppedBefore).isEqualTo(1);
    }
}
