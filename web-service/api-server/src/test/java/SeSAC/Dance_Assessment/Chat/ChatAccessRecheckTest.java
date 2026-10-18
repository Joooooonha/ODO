package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.RawStompClient;
import SeSAC.Dance_Assessment.Realtime.ChatDestinations;
import SeSAC.Dance_Assessment.Realtime.ChatRealtimeMetrics;
import SeSAC.Dance_Assessment.Realtime.ControlEvent;
import SeSAC.Dance_Assessment.Realtime.ControlEventPublisher;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;

/**
 * [6.4·11.5] 제어 이벤트를 일부러 누락시켜도 5초 재확인이 약 5초 안에 고친다. 재확인이 고친 변경은
 * "이벤트보다 먼저 발견한 변경"으로 센다(= 이벤트 유실 수).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatAccessRecheckTest {

    /** 재확인 주기 5초 + 여유 */
    private static final Duration WITHIN_RECHECK = Duration.ofSeconds(8);

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
    ChatRealtimeMetrics metrics;

    @MockitoSpyBean
    ControlEventPublisher controlEvents;

    TestUsers users;
    TestTeams teams;
    TestChat chat;
    final List<RawStompClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        users = new TestUsers(mvc, om);
        teams = new TestTeams(mvc, om);
        chat = new TestChat(mvc, om, chatMessageService, jdbc);
        // 제어 이벤트가 하나도 나가지 않는다(Redis 장애·발행 실패를 흉내)
        doNothing().when(controlEvents).revoked(anyLong(), anyLong());
        doNothing().when(controlEvents).granted(anyLong(), anyLong());
    }

    @AfterEach
    void tearDown() throws Exception {
        for (RawStompClient c : clients) {
            c.close();
        }
    }

    @Test
    @DisplayName("추방 이벤트가 유실돼도 약 5초 안에 REVOKED가 오고, 그 뒤로는 그 방 메시지를 받지 않는다")
    void lostRevokeIsFixedByRecheck() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = teams.teamOf(leader, member);
        long room = chat.teamRoomId(leader, teamId);
        RawStompClient memberWs = RawStompClient.connect(port, member.cookie());
        clients.add(memberWs);
        memberWs.subscribe(ChatDestinations.roomTopic(room));
        memberWs.awaitSubscriptionsProcessed();
        long before = metrics.recheckCorrections(ControlEvent.REVOKED);

        long kickedAt = System.currentTimeMillis();
        teams.kick(leader, teamId, member);
        JsonNode event = memberWs.nextEvent(WITHIN_RECHECK);
        long tookMs = System.currentTimeMillis() - kickedAt;

        assertThat(event.get("type").asText()).isEqualTo("REVOKED");
        assertThat(tookMs).as("재확인 주기 안팎").isLessThan(WITHIN_RECHECK.toMillis());
        assertThat(metrics.recheckCorrections(ControlEvent.REVOKED) - before).isEqualTo(1);

        chat.send(leader, room, "재확인 뒤");
        memberWs.expectNone(f -> "MESSAGE".equals(f.command()) && ChatDestinations.roomTopic(room).equals(f.destination())
                && f.body().contains("재확인 뒤"), Duration.ofSeconds(1));
        assertThat(memberWs.isOpen()).isTrue();
    }

    @Test
    @DisplayName("재가입 이벤트가 유실돼도 약 5초 안에 GRANTED가 온다")
    void lostGrantIsFixedByRecheck() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = teams.teamOf(leader, member);
        long room = chat.teamRoomId(leader, teamId);
        teams.leave(member, teamId);
        RawStompClient memberWs = RawStompClient.connect(port, member.cookie());
        clients.add(memberWs);
        long before = metrics.recheckCorrections(ControlEvent.GRANTED);

        teams.rejoin(member, teams.joinCode(leader, teamId));
        JsonNode event = memberWs.nextEvent(WITHIN_RECHECK);

        assertThat(event.get("type").asText()).isEqualTo("GRANTED");
        assertThat(event.get("roomId").asLong()).isEqualTo(room);
        assertThat(metrics.recheckCorrections(ControlEvent.GRANTED) - before).isGreaterThanOrEqualTo(1);
    }
}
