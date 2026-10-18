package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.RawStompClient;
import SeSAC.Dance_Assessment.Realtime.ChatRealtimeMetrics;
import SeSAC.Dance_Assessment.Realtime.ChatReplySender;
import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.Service.Chat.ChatReply;
import SeSAC.Dance_Assessment.Service.Chat.MessageWriter;
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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 웹소켓 경로에서 장애를 주입해야 확인할 수 있는 것들(4.3·4.4, 11.3).
 *
 * <p>스파이는 트랜잭션 프록시 안쪽을 감싼다 — 스파이에서 던진 예외는 저장 트랜잭션 안에서 터져 롤백된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatWebSocketFaultTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

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
    MessageWriter writer;

    @MockitoSpyBean
    ChatReplySender replySender;

    TestUsers users;
    TestTeams teams;
    TestChat chat;
    LoggedIn me;
    long room;
    final List<RawStompClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(mvc, om);
        teams = new TestTeams(mvc, om);
        chat = new TestChat(mvc, om, chatMessageService, jdbc);
        me = users.create("나");
        room = chat.teamRoomId(me, teams.createTeam(me, "장애 팀"));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (RawStompClient c : clients) {
            c.close();
        }
    }

    private RawStompClient connect() throws Exception {
        RawStompClient c = RawStompClient.connect(port, me.cookie());
        clients.add(c);
        return c;
    }

    private static QueryTimeoutException statementTimeout() {
        return new QueryTimeoutException("주입: 문장 시간 초과",
                new SQLException("canceling statement due to statement timeout", "57014"));
    }

    /** 이 UUID들의 첫 {@code times}번 저장 시도를 재시도 대상 오류로 실패시킨다. */
    private void failFirstAttempts(Set<String> clientMessageIds, int times) {
        Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            String id = inv.getArgument(2).toString();
            if (clientMessageIds.contains(id) && counts.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet() <= times) {
                throw statementTimeout();
            }
            return inv.callRealMethod();
        }).when(writer).writeText(anyLong(), anyLong(), any(), anyString());
    }

    /** 저장마다 일부러 늦춘다(대기열이 쌓이게). */
    private void slowWrites(long millis) {
        doAnswer(inv -> {
            Thread.sleep(millis);
            return inv.callRealMethod();
        }).when(writer).writeText(anyLong(), anyLong(), any(), anyString());
    }

    private List<String> savedOrder() {
        return jdbc.queryForList("SELECT client_message_id::text FROM message WHERE room_id = ? AND type = 'TEXT' ORDER BY seq",
                String.class, room);
    }

    private static List<String> ids(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(UUID.randomUUID().toString());
        }
        return out;
    }

    @Test
    @DisplayName("[4.3] 재시도 소진 → SERVER_RETRY_EXHAUSTED 1건, 멈춘 동안 보낸 것은 응답 없이 버림 → resume부터 처리, 저장 순서 = 보낸 순서")
    void haltAndResume() throws Exception {
        List<String> sent = ids(4);
        failFirstAttempts(Set.of(sent.get(1)), 4);
        RawStompClient c = connect();

        for (String id : sent) {
            c.sendMessage(room, id, "m", false);
        }
        assertThat(c.nextAck(WAIT).get("clientMessageId").asText()).isEqualTo(sent.get(0));
        JsonNode error = c.nextAck(WAIT);
        assertThat(error.get("code").asText()).isEqualTo("SERVER_RETRY_EXHAUSTED");
        assertThat(error.get("clientMessageId").asText()).isEqualTo(sent.get(1));
        c.expectNone(f -> f.destination() != null && f.destination().endsWith("/queue/acks"), Duration.ofSeconds(1));
        assertThat(savedOrder()).containsExactly(sent.get(0));

        // 브라우저: 확정 못 받은 것부터 원래 순서대로, 첫 메시지에만 resume
        c.sendMessage(room, sent.get(1), "m", true);
        c.sendMessage(room, sent.get(2), "m", false);
        c.sendMessage(room, sent.get(3), "m", false);
        for (int i = 1; i < 4; i++) {
            assertThat(c.nextAck(WAIT).get("clientMessageId").asText()).isEqualTo(sent.get(i));
        }
        assertThat(savedOrder()).containsExactlyElementsOf(sent);
    }

    @Test
    @DisplayName("[11.3] 멈춤 표시는 오류 응답 전송보다 먼저다 — 응답 전송이 예외를 던져도 다음 메시지는 처리되지 않는다")
    void haltIsSetBeforeReply() throws Exception {
        List<String> sent = ids(2);
        failFirstAttempts(Set.of(sent.get(0)), 4);
        doAnswer(inv -> {
            if (inv.getArgument(1) instanceof ChatReply.Error) {
                throw new IllegalStateException("주입: 응답 전송 실패(이미 끊긴 세션 등)");
            }
            return inv.callRealMethod();
        }).when(replySender).send(any(), any());
        RawStompClient c = connect();

        c.sendMessage(room, sent.get(0), "실패할 것", false);
        c.sendMessage(room, sent.get(1), "멈춘 동안 온 것", false);
        Thread.sleep(2000);   // 재시도(약 0.7초) + 다음 메시지가 처리될 시간

        assertThat(savedOrder()).as("멈춤이 걸려 있어 두 번째도 저장되지 않는다").isEmpty();
        c.sendMessage(room, sent.get(0), "실패할 것", true);
        c.sendMessage(room, sent.get(1), "멈춘 동안 온 것", false);
        c.nextAck(WAIT);
        c.nextAck(WAIT);
        assertThat(savedOrder()).containsExactlyElementsOf(sent);
    }

    @Test
    @DisplayName("[4.3 필수] 대기열에 메시지가 남은 채 연결이 끊김 → 끊긴 뒤 새로 커밋된 것은 진행 중이던 1건 이하 → 새 세션이 같은 UUID로 다시 보내면 순서대로, 중복 없이")
    void disconnectWithQueuedMessages() throws Exception {
        slowWrites(200);
        List<String> sent = ids(10);
        RawStompClient old = connect();
        for (String id : sent) {
            old.sendMessage(room, id, "m", false);
        }
        old.nextAck(WAIT);                  // 첫 번째가 확정됐다
        int savedAtDrop = savedOrder().size();
        old.drop();
        Thread.sleep(2500);                 // 대기열이 남아 있었다면 다 처리됐을 시간(10 × 200ms)

        int savedAfterDrop = savedOrder().size();
        assertThat(savedAfterDrop - savedAtDrop).as("끊긴 뒤 새로 커밋된 메시지(진행 중이던 1건 이하)").isLessThanOrEqualTo(1);

        RawStompClient fresh = connect();
        for (String id : sent) {
            fresh.sendMessage(room, id, "m", false);
        }
        for (String id : sent) {
            assertThat(fresh.nextAck(WAIT).get("clientMessageId").asText()).isEqualTo(id);
        }
        assertThat(savedOrder()).containsExactlyElementsOf(sent);
        chat.assertContiguous(room);
    }

    @Test
    @DisplayName("[4.4] 확정 없이 101개 넘게 밀어 넣으면 ERROR QUEUE_LIMIT_EXCEEDED 후 연결 종료, 이미 저장된 것은 보낸 순서의 앞부분이다")
    void queueLimitDisconnects() throws Exception {
        slowWrites(100);
        long before = metrics.disconnects(ChatRealtimeMetrics.QUEUE_LIMIT);
        List<String> sent = ids(110);
        RawStompClient c = connect();
        for (String id : sent) {
            try {
                c.sendMessage(room, id, "m", false);
            } catch (Exception closedAlready) {
                break;
            }
        }

        RawStompClient.Frame error = c.expect("ERROR", WAIT);
        assertThat(error.headers().get("message")).isEqualTo("QUEUE_LIMIT_EXCEEDED");
        c.awaitClosed(WAIT);
        assertThat(metrics.disconnects(ChatRealtimeMetrics.QUEUE_LIMIT) - before).isEqualTo(1);

        Thread.sleep(500);
        List<String> saved = savedOrder();
        assertThat(saved.size()).isLessThan(sent.size());
        assertThat(saved).containsExactlyElementsOf(sent.subList(0, saved.size()));
        chat.assertContiguous(room);
    }

    @Test
    @DisplayName("[4.4] 전송 창(50)을 지키는 클라이언트는 재시도 소진 → 즉시 재개를 반복해도 끊기지 않는다(옛 사본 49 + 새 사본 50 < 100)")
    void windowRespectingClientIsNeverDisconnected() throws Exception {
        long before = metrics.disconnects(ChatRealtimeMetrics.QUEUE_LIMIT);
        List<String> round1 = ids(50);
        List<String> round2 = ids(50);
        failFirstAttempts(Set.of(round1.get(0), round2.get(0)), 4);
        RawStompClient c = connect();

        for (List<String> batch : List.of(round1, round2)) {
            for (String id : batch) {
                c.sendMessage(room, id, "m", false);
            }
            assertThat(c.nextAck(WAIT).get("code").asText()).isEqualTo("SERVER_RETRY_EXHAUSTED");
            // 오류를 받자마자 전부 다시 보낸다(대기열에 옛 사본이 남아 있는 동안)
            for (int i = 0; i < batch.size(); i++) {
                c.sendMessage(room, batch.get(i), "m", i == 0);
            }
            for (String id : batch) {
                assertThat(c.nextAck(WAIT).get("clientMessageId").asText()).isEqualTo(id);
            }
        }

        assertThat(c.isOpen()).isTrue();
        assertThat(metrics.disconnects(ChatRealtimeMetrics.QUEUE_LIMIT) - before).isZero();
        List<String> all = new ArrayList<>(round1);
        all.addAll(round2);
        assertThat(savedOrder()).containsExactlyElementsOf(all);
    }
}
