package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.RawStompClient;
import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.TestChat;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메시지 처리 스레드 수 설정(10/20 작업 3-2, {@code chat.ws.inbound-threads}). 스레드를 8개로 늘려도
 * 세션별 순서(보낸 순서 = 확정 순서 = 저장 순서)와 방 순번(빈틈·중복 없음)이 그대로인지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "chat.ws.inbound-threads=8")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatInboundThreadsTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final int SESSIONS = 6;
    private static final int PER_SESSION = 40;

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
    @Qualifier("clientInboundChannelExecutor")
    Executor inbound;

    @Autowired
    @Qualifier("clientOutboundChannelExecutor")
    Executor outbound;

    final List<RawStompClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (RawStompClient c : clients) {
            c.close();
        }
    }

    @Test
    @DisplayName("설정한 수만큼 인바운드 스레드를 쓴다(아웃바운드는 기본값 그대로)")
    void threadCountIsConfigurable() {
        assertThat(((ThreadPoolTaskExecutor) inbound).getCorePoolSize()).isEqualTo(8);
        assertThat(((ThreadPoolTaskExecutor) outbound).getCorePoolSize()).isEqualTo(Runtime.getRuntime().availableProcessors() * 2);
    }

    @Test
    @DisplayName("[B6] 스레드 8개 · 한 방에 세션 6개가 동시에 40개씩 — 세션마다 확정·저장 순서가 보낸 순서, 방 순번은 빈틈·중복 없음")
    void sessionOrderHoldsWithMoreThreads() throws Exception {
        TestUsers users = new TestUsers(mvc, om);
        TestChat chat = new TestChat(mvc, om, chatMessageService, jdbc);
        LoggedIn me = users.create("나");
        long room = chat.teamRoomId(me, new TestTeams(mvc, om).createTeam(me, "스레드 팀"));

        List<RawStompClient> sessions = new ArrayList<>();
        List<List<String>> sent = new ArrayList<>();
        for (int s = 0; s < SESSIONS; s++) {
            RawStompClient c = RawStompClient.connect(port, me.cookie());
            clients.add(c);
            sessions.add(c);
            sent.add(new ArrayList<>());
        }
        // 세션들을 번갈아 보내 같은 시각에 여러 세션의 메시지가 실행기에 쌓이게 한다
        for (int i = 0; i < PER_SESSION; i++) {
            for (int s = 0; s < SESSIONS; s++) {
                String id = UUID.randomUUID().toString();
                sent.get(s).add(id);
                sessions.get(s).sendMessage(room, id, "s" + s + "-" + i, false);
            }
        }

        for (int s = 0; s < SESSIONS; s++) {
            List<String> acked = new ArrayList<>();
            long lastSeq = 0;
            for (int i = 0; i < PER_SESSION; i++) {
                JsonNode ack = sessions.get(s).nextAck(WAIT);
                acked.add(ack.get("clientMessageId").asText());
                assertThat(ack.get("seq").asLong()).isGreaterThan(lastSeq);
                lastSeq = ack.get("seq").asLong();
            }
            assertThat(acked).containsExactlyElementsOf(sent.get(s));
        }

        List<String> stored = jdbc.queryForList(
                "SELECT client_message_id::text FROM message WHERE room_id = ? ORDER BY seq", String.class, room);
        for (List<String> mine : sent) {
            assertThat(stored.stream().filter(mine::contains).toList()).containsExactlyElementsOf(mine);
        }
        assertThat(stored).hasSize(SESSIONS * PER_SESSION);
        chat.assertContiguous(room);
    }
}
