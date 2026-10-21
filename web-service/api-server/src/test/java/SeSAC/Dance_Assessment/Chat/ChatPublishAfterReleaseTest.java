package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.RawStompClient;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발행을 커넥션 반납 뒤로({@code chat.send.publish-after-release}). 켜면 사람이 보낸 메시지의
 * 발행이 커넥션을 돌려준 뒤에 일어나고, 확정·저장·방송 순서와 방 순번은 그대로인지 본다(스레드 8개).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"chat.send.publish-after-release=true", "chat.ws.inbound-threads=8"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatPublishAfterReleaseTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final int SESSIONS = 4;
    private static final int PER_SESSION = 25;

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

    @Autowired
    RoomSubscriptions roomSubscriptions;

    final List<RawStompClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (RawStompClient c : clients) {
            c.close();
        }
    }

    @Test
    @DisplayName("켜면 메시지 발행은 커넥션 반납 뒤(released) — 세션마다 확정·저장 순서 = 보낸 순서, 구독자는 전부 받고 방 순번은 빈틈·중복 없음")
    void publishesAfterConnectionIsReleased() throws Exception {
        TestUsers users = new TestUsers(mvc, om);
        TestChat chat = new TestChat(mvc, om, chatMessageService, jdbc);
        LoggedIn me = users.create("나");
        long room = chat.teamRoomId(me, new TestTeams(mvc, om).createTeam(me, "발행 팀"));

        RawStompClient watcher = RawStompClient.connect(port, me.cookie());
        clients.add(watcher);
        watcher.subscribe(ChatDestinations.roomTopic(room));
        watcher.awaitSubscriptionsProcessed();
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (roomSubscriptions.sessions(room).size() != 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        long heldBefore = metrics.publishConnections("held");
        long releasedBefore = metrics.publishConnections("released");

        List<RawStompClient> sessions = new ArrayList<>();
        List<List<String>> sent = new ArrayList<>();
        for (int s = 0; s < SESSIONS; s++) {
            RawStompClient c = RawStompClient.connect(port, me.cookie());
            clients.add(c);
            sessions.add(c);
            sent.add(new ArrayList<>());
        }
        for (int i = 0; i < PER_SESSION; i++) {
            for (int s = 0; s < SESSIONS; s++) {
                String id = UUID.randomUUID().toString();
                sent.get(s).add(id);
                sessions.get(s).sendMessage(room, id, "s" + s + "-" + i, false);
            }
        }
        for (int s = 0; s < SESSIONS; s++) {
            List<String> acked = new ArrayList<>();
            for (int i = 0; i < PER_SESSION; i++) {
                acked.add(sessions.get(s).nextAck(WAIT).get("clientMessageId").asText());
            }
            assertThat(acked).containsExactlyElementsOf(sent.get(s));
        }

        int total = SESSIONS * PER_SESSION;
        List<Long> delivered = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            JsonNode m = watcher.nextRoomMessage(room, WAIT);
            delivered.add(m.get("seq").asLong());
        }
        assertThat(delivered).doesNotHaveDuplicates().hasSize(total);
        assertThat(delivered.stream().sorted().toList()).isEqualTo(java.util.stream.LongStream.rangeClosed(1, total).boxed().toList());

        List<String> stored = jdbc.queryForList(
                "SELECT client_message_id::text FROM message WHERE room_id = ? ORDER BY seq", String.class, room);
        for (List<String> mine : sent) {
            assertThat(stored.stream().filter(mine::contains).toList()).containsExactlyElementsOf(mine);
        }
        chat.assertContiguous(room);

        assertThat(metrics.publishConnections("released") - releasedBefore).isEqualTo(total);
        assertThat(metrics.publishConnections("held") - heldBefore).isZero();
    }
}
