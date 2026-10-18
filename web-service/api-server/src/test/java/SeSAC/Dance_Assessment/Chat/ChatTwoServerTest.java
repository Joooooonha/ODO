package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.DanceAssessmentApplication;
import SeSAC.Dance_Assessment.RawStompClient;
import SeSAC.Dance_Assessment.Realtime.ChatDestinations;
import SeSAC.Dance_Assessment.Realtime.ChatRealtimeMetrics;
import SeSAC.Dance_Assessment.Realtime.RoomSubscriptions;
import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.TestChat;
import SeSAC.Dance_Assessment.TestContainers;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 2대(5.2): 같은 PostgreSQL·Redis를 쓰는 두 번째 서버를 이 JVM 안에 하나 더 띄운다.
 * S1에 붙은 사람의 메시지를 S2에 붙은 사람이 Redis를 거쳐 받는지, Redis가 멈춰도 저장·확정은 되는지.
 *
 * <p>Compose(앱 2대 + nginx)로 브라우저에서 보는 재현 절차는 단계 3 보고에서 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatTwoServerTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private static ConfigurableApplicationContext server2;
    private static int port2;

    @LocalServerPort
    int port1;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    ChatMessageService chatMessageService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ChatRealtimeMetrics metrics1;

    @Autowired
    RoomSubscriptions rooms1;

    TestUsers users;
    TestTeams teams;
    TestChat chat;
    final List<RawStompClient> clients = new ArrayList<>();

    @BeforeAll
    static void startSecondServer() {
        server2 = new SpringApplicationBuilder(DanceAssessmentApplication.class)
                .profiles("dev")
                .run("--server.port=0");
        port2 = ((ServletWebServerApplicationContext) server2).getWebServer().getPort();
    }

    @AfterAll
    static void stopSecondServer() {
        if (server2 != null) {
            server2.close();
        }
    }

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
        unpauseRedis();
    }

    private RawStompClient connect(int port, LoggedIn user, long room) throws Exception {
        RawStompClient c = RawStompClient.connect(port, user.cookie());
        clients.add(c);
        c.subscribe(ChatDestinations.roomTopic(room));
        return c;
    }

    @Test
    @DisplayName("[11.5] S1에 붙은 사람의 메시지를 S2에 붙은 사람이 실시간으로 받고, 반대 방향도 된다. 보낸 사람도 같은 경로로 받는다")
    void crossServerDelivery() throws Exception {
        LoggedIn a = users.create("에스원");
        LoggedIn b = users.create("에스투");
        long room = chat.teamRoomId(a, teams.teamOf(a, b));
        RawStompClient onS1 = connect(port1, a, room);
        RawStompClient onS2 = connect(port2, b, room);
        awaitRedisSubscribers(room, 2);

        onS1.sendMessage(room, UUID.randomUUID().toString(), "S1에서", false);
        assertThat(onS1.nextAck(WAIT).get("type").asText()).isEqualTo("ACK");
        JsonNode atS2 = onS2.nextRoomMessage(room, WAIT);
        assertThat(atS2.get("content").asText()).isEqualTo("S1에서");
        assertThat(atS2.get("senderNickname").asText()).isEqualTo("에스원");
        assertThat(onS1.nextRoomMessage(room, WAIT).get("seq").asLong()).isEqualTo(atS2.get("seq").asLong());

        onS2.sendMessage(room, UUID.randomUUID().toString(), "S2에서", false);
        assertThat(onS1.nextRoomMessage(room, WAIT).get("content").asText()).isEqualTo("S2에서");
    }

    @Test
    @DisplayName("[11.5·P6] Redis가 응답하지 않는 동안에도 저장과 확정 응답은 된다(발행 실패만 기록). Redis가 돌아오면 다음 메시지부터 다시 전달되고, 받는 쪽은 번호로 빈틈을 안다")
    void redisStallKeepsSaving() throws Exception {
        LoggedIn a = users.create("보내는 사람");
        LoggedIn b = users.create("받는 사람");
        long room = chat.teamRoomId(a, teams.teamOf(a, b));
        RawStompClient onS1 = connect(port1, a, room);
        RawStompClient onS2 = connect(port2, b, room);
        awaitRedisSubscribers(room, 2);
        long failuresBefore = metrics1.publishFailures();

        pauseRedis();
        long started = System.nanoTime();
        onS1.sendMessage(room, UUID.randomUUID().toString(), "Redis가 멈춘 동안", false);
        JsonNode ack = onS1.nextAck(WAIT);
        long ackMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        long stalledSeq = ack.get("seq").asLong();
        assertThat(ack.get("type").asText()).isEqualTo("ACK");
        assertThat(ackMillis).as("발행 시간 제한(500ms) 안팎에서 확정").isLessThan(3000);
        assertThat(metrics1.publishFailures() - failuresBefore).isEqualTo(1);
        onS2.expectNone(f -> "MESSAGE".equals(f.command()) && ChatDestinations.roomTopic(room).equals(f.destination()),
                Duration.ofMillis(800));

        unpauseRedis();
        long nextSeq = 0;
        long deadline = System.currentTimeMillis() + 15_000;
        // 컨테이너의 구독 연결이 회복될 때까지 몇 번 보낼 수 있다. 받은 쪽이 멈춘 동안 번호보다 큰 번호를 받으면 된다.
        while (nextSeq <= stalledSeq && System.currentTimeMillis() < deadline) {
            onS1.sendMessage(room, UUID.randomUUID().toString(), "돌아왔다", false);
            onS1.nextAck(WAIT);
            try {
                JsonNode got = onS2.nextRoomMessage(room, Duration.ofSeconds(2));
                nextSeq = Math.max(nextSeq, got.get("seq").asLong());
            } catch (AssertionError notYet) {
                // 아직 회복 중
            }
        }
        assertThat(nextSeq).as("Redis 복구 뒤 전달 재개, 멈춘 동안의 번호(%d) 뒤", stalledSeq).isGreaterThan(stalledSeq);
        chat.assertContiguous(room);
    }

    private void awaitRedisSubscribers(long room, long expected) throws InterruptedException {
        String channel = ChatDestinations.roomChannel(room).getTopic();
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (TestContainers.redisSubscribers(channel) != expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Redis 구독 서버 수 " + TestContainers.redisSubscribers(channel) + " ≠ " + expected);
            }
            Thread.sleep(50);
        }
    }

    private static void pauseRedis() {
        var redis = TestContainers.redis();
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
    }

    private static void unpauseRedis() {
        var redis = TestContainers.redis();
        var state = redis.getDockerClient().inspectContainerCmd(redis.getContainerId()).exec().getState();
        if (Boolean.TRUE.equals(state.getPaused())) {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }
}
