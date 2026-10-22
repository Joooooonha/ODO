package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.Realtime.ChatRealtimeMetrics;
import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.Service.Chat.ChatReadMetrics;
import SeSAC.Dance_Assessment.Service.Chat.ReadBulkhead;
import SeSAC.Dance_Assessment.TestChat;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 읽음 벌크헤드 — 켠 상태(K = 2). 끈 상태(기본 0)는 {@link ChatReadTest}.
 * 허가를 테스트가 직접 쥐어 "동시 처리 K건이 이미 돌고 있는" 상황을 만든다(실제 경합을 기다리지 않게).
 */
@SpringBootTest(properties = "chat.read.bulkhead.max-concurrent=2")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatReadBulkheadTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    ChatMessageService chatMessageService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReadBulkhead bulkhead;

    @Autowired
    ChatReadMetrics readMetrics;

    @Autowired
    ChatRealtimeMetrics realtimeMetrics;

    @Autowired
    MeterRegistry registry;

    TestUsers users;
    TestTeams teams;
    TestChat chat;
    int held;

    @BeforeEach
    void setUp() {
        users = new TestUsers(mvc, om);
        teams = new TestTeams(mvc, om);
        chat = new TestChat(mvc, om, chatMessageService, jdbc);
    }

    @AfterEach
    void releaseHeld() {
        for (; held > 0; held--) {
            bulkhead.release();
        }
    }

    private void holdAll() {
        while (bulkhead.tryAcquire()) {
            held++;
        }
        assertThat(held).isEqualTo(2);
    }

    private ResultActions report(LoggedIn user, long roomId, long seq) throws Exception {
        return mvc.perform(post("/api/chat/rooms/{id}/read", roomId).cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("seq", seq))));
    }

    private long acquiredConnections() {
        Timer t = registry.find("hikaricp.connections.acquire").timer();
        return t == null ? 0 : t.count();
    }

    @Test
    @DisplayName("동시 처리가 K건이면 다음 보고는 기다리지 않고 429 READ_BUSY — 위치·발행 없음, rejected로 센다, 커넥션도 잡지 않는다")
    void rejectsWhenFull() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        long roomId = chat.teamRoomId(leader, teams.teamOf(leader, member));
        chat.send(leader, roomId, "하나");
        chat.send(leader, roomId, "둘");
        long last = chat.lastSeq(roomId);
        long before = (long) chat.member(roomId, member.id())[1];
        long rejected = readMetrics.requests(ChatReadMetrics.REJECTED);
        long published = realtimeMetrics.publishes(ChatRealtimeMetrics.READ);

        holdAll();
        long connections = acquiredConnections();
        for (int i = 0; i < 5; i++) {
            report(member, roomId, last).andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("READ_BUSY"));
        }
        assertThat(acquiredConnections()).as("거절은 DB를 쓰기 전 — 커넥션을 빌리지 않는다").isEqualTo(connections);
        assertThat(readMetrics.requests(ChatReadMetrics.REJECTED) - rejected).isEqualTo(5);
        assertThat(realtimeMetrics.publishes(ChatRealtimeMetrics.READ)).isEqualTo(published);
        assertThat((long) chat.member(roomId, member.id())[1]).isEqualTo(before);

        releaseHeld();
        report(member, roomId, last).andExpect(status().isNoContent());
        assertThat((long) chat.member(roomId, member.id())[1]).as("허가가 돌아오면 같은 값을 다시 보내 반영된다").isEqualTo(last);
    }

    @Test
    @DisplayName("허가는 성공·noop·오류(400·403) 모두에서 돌려준다 — 끝난 뒤 동시 처리 0, 허가 K개 그대로")
    void permitsReturnOnEveryOutcome() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        LoggedIn outsider = users.create("남");
        long roomId = chat.teamRoomId(leader, teams.teamOf(leader, member));
        chat.send(leader, roomId, "하나");
        long last = chat.lastSeq(roomId);

        report(member, roomId, last).andExpect(status().isNoContent());
        report(member, roomId, last).andExpect(status().isNoContent());          // noop
        report(member, roomId, last + 5).andExpect(status().isBadRequest());     // READ_SEQ_AHEAD
        report(outsider, roomId, last).andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/rooms/{id}/read", roomId).cookie(member.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"seq\":-1}")).andExpect(status().isBadRequest());

        assertThat(registry.get("chat.read.inflight").gauge().value()).isZero();
        holdAll();   // K개를 다시 모두 받을 수 있어야 한다
    }
}
