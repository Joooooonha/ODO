package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.RawStompClient;
import SeSAC.Dance_Assessment.Realtime.ChatDestinations;
import SeSAC.Dance_Assessment.Realtime.ChatRealtimeMetrics;
import SeSAC.Dance_Assessment.Realtime.RoomSubscriptions;
import SeSAC.Dance_Assessment.Realtime.TokenExpiryChecker;
import SeSAC.Dance_Assessment.Security.JwtCookie;
import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.TestChat;
import SeSAC.Dance_Assessment.TestContainers;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 웹소켓·STOMP(5.1): 핸드셰이크 인증과 Origin, 확정·오류가 보낸 세션에만 가는지, 보낸 순서, 구독
 * 규칙, 토큰 만료, "메시지 하나에 프레임 하나", 연결 종료 정리. 실제 서버(임의 포트) + Redis로 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatWebSocketTest {

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
    TokenExpiryChecker tokenExpiryChecker;

    @Autowired
    ChatRealtimeMetrics metrics;

    @Autowired
    RoomSubscriptions roomSubscriptions;

    @Autowired
    MeterRegistry meterRegistry;

    @Value("${jwt.secret}")
    String jwtSecret;

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

    private RawStompClient connect(LoggedIn user) throws Exception {
        RawStompClient c = RawStompClient.connect(port, user.cookie());
        clients.add(c);
        return c;
    }

    // ===== 핸드셰이크 =====

    @Test
    @DisplayName("[11.1] 쿠키 없는·위조·만료 쿠키의 핸드셰이크는 401, 허용 목록 밖 Origin은 403, 정상이면 CONNECTED에 사용자 id가 실린다")
    void handshakeAuthAndOrigin() throws Exception {
        LoggedIn me = users.create("나");

        assertThatThrownBy(() -> RawStompClient.open(port, null, RawStompClient.ORIGIN))
                .hasStackTraceContaining("401");
        assertThatThrownBy(() -> RawStompClient.open(port, new Cookie(JwtCookie.NAME, "forged"), RawStompClient.ORIGIN))
                .hasStackTraceContaining("401");
        assertThatThrownBy(() -> RawStompClient.open(port, tokenCookie(me.id(), Instant.now().minusSeconds(5)),
                RawStompClient.ORIGIN)).hasStackTraceContaining("401");
        assertThatThrownBy(() -> RawStompClient.open(port, me.cookie(), "https://evil.example"))
                .hasStackTraceContaining("403");

        RawStompClient ok = RawStompClient.open(port, me.cookie(), RawStompClient.ORIGIN);
        clients.add(ok);
        ok.sendFrame("CONNECT", Map.of("accept-version", "1.2", "host", "localhost", "heart-beat", "0,0"), "");
        RawStompClient.Frame connected = ok.expect("CONNECTED", WAIT);
        assertThat(connected.headers().get("user-name")).isEqualTo(String.valueOf(me.id()));
    }

    // ===== 전송과 응답 =====

    @Test
    @DisplayName("[11.3] 확정 응답은 보낸 세션에만 가고 같은 사용자의 다른 탭에는 가지 않는다. 방송은 두 탭 모두 받는다(보낸 서버도 Redis 경로로)")
    void ackGoesOnlyToSendingSession() throws Exception {
        LoggedIn me = users.create("나");
        long room = chat.teamRoomId(me, teams.createTeam(me, "탭 팀"));
        RawStompClient tab1 = connect(me);
        RawStompClient tab2 = connect(me);
        tab1.subscribe(ChatDestinations.roomTopic(room));
        tab2.subscribe(ChatDestinations.roomTopic(room));
        awaitSubscribed(room, 2);

        String id = UUID.randomUUID().toString();
        tab1.sendMessage(room, id, "탭1에서", false);

        JsonNode ack = tab1.nextAck(WAIT);
        assertThat(ack.get("type").asText()).isEqualTo("ACK");
        assertThat(ack.get("clientMessageId").asText()).isEqualTo(id);
        assertThat(ack.get("seq").asLong()).isEqualTo(1);
        assertThat(tab1.nextRoomMessage(room, WAIT).get("content").asText()).isEqualTo("탭1에서");
        assertThat(tab2.nextRoomMessage(room, WAIT).get("seq").asLong()).isEqualTo(1);
        tab2.expectNone(f -> f.destination() != null && f.destination().endsWith("/queue/acks"), Duration.ofMillis(500));
    }

    @Test
    @DisplayName("[B6] 한 세션에서 연달아 보낸 30개 — 확정 순서와 저장 순서가 보낸 순서와 같다")
    void sessionOrderIsSendOrder() throws Exception {
        LoggedIn me = users.create("나");
        long room = chat.teamRoomId(me, teams.createTeam(me, "순서 팀"));
        RawStompClient c = connect(me);

        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String id = UUID.randomUUID().toString();
            sent.add(id);
            c.sendMessage(room, id, "m" + i, false);
        }
        List<String> acked = new ArrayList<>();
        long lastSeq = 0;
        for (int i = 0; i < 30; i++) {
            JsonNode ack = c.nextAck(WAIT);
            acked.add(ack.get("clientMessageId").asText());
            assertThat(ack.get("seq").asLong()).isGreaterThan(lastSeq);
            lastSeq = ack.get("seq").asLong();
        }
        assertThat(acked).containsExactlyElementsOf(sent);
        assertThat(jdbc.queryForList("SELECT client_message_id::text FROM message WHERE room_id = ? ORDER BY seq",
                String.class, room)).containsExactlyElementsOf(sent);
    }

    @Test
    @DisplayName("읽지 못하는 본문은 INVALID_MESSAGE(clientMessageId 없음), 멤버가 아닌 방으로 보내면 NOT_MEMBER — 둘 다 세션은 멈추지 않는다")
    void invalidAndNotMember() throws Exception {
        LoggedIn me = users.create("나");
        LoggedIn other = users.create("남");
        long mine = chat.teamRoomId(me, teams.createTeam(me, "내 팀"));
        long theirs = chat.teamRoomId(other, teams.createTeam(other, "남의 팀"));
        RawStompClient c = connect(me);

        c.sendRaw(mine, "{not json");
        JsonNode invalid = c.nextAck(WAIT);
        assertThat(invalid.get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(invalid.get("clientMessageId").isNull()).isTrue();

        String id = UUID.randomUUID().toString();
        c.sendMessage(theirs, id, "남의 방", false);
        assertThat(c.nextAck(WAIT).get("code").asText()).isEqualTo("NOT_MEMBER");

        c.sendMessage(mine, UUID.randomUUID().toString(), "계속 된다", false);
        assertThat(c.nextAck(WAIT).get("type").asText()).isEqualTo("ACK");
    }

    // ===== 구독 =====

    @Test
    @DisplayName("[11.3] 남의 방 구독은 거부되고(REVOKED 알림) 그 방 메시지를 받지 않는다. 형식이 다른 목적지도 거부")
    void subscribeRules() throws Exception {
        LoggedIn me = users.create("나");
        LoggedIn other = users.create("남");
        long theirs = chat.teamRoomId(other, teams.createTeam(other, "닫힌 팀"));
        long deniedBefore = metricsDenied();
        RawStompClient c = connect(me);

        c.subscribe(ChatDestinations.roomTopic(theirs));
        JsonNode event = c.nextEvent(WAIT);
        assertThat(event.get("type").asText()).isEqualTo("REVOKED");
        assertThat(event.get("roomId").asLong()).isEqualTo(theirs);

        c.subscribe("/topic/rooms.abc");
        c.subscribe("/topic/anything");
        c.subscribe("/queue/acks-user" + UUID.randomUUID());

        chat.send(other, theirs, "보이면 안 된다");
        c.expectNone(f -> "MESSAGE".equals(f.command()) && f.destination() != null
                && f.destination().startsWith("/topic/"), Duration.ofSeconds(1));
        assertThat(roomSubscriptions.sessions(theirs)).isEmpty();
        assertThat(metricsDenied() - deniedBefore).isEqualTo(4);
    }

    @Test
    @DisplayName("[5.3] 구독 해제·연결 종료 시 방 구독표에서 빠지고, 마지막 구독자가 빠지면 Redis 방 채널 구독도 해제된다")
    void unsubscribeAndDisconnectCleanUp() throws Exception {
        LoggedIn me = users.create("나");
        long room = chat.teamRoomId(me, teams.createTeam(me, "정리 팀"));
        RawStompClient a = connect(me);
        RawStompClient b = connect(me);
        String subA = a.subscribe(ChatDestinations.roomTopic(room));
        b.subscribe(ChatDestinations.roomTopic(room));
        awaitSubscribed(room, 2);
        assertThat(redisSubscribers(room)).isEqualTo(1);

        a.unsubscribe(subA);
        awaitSubscribed(room, 1);
        assertThat(redisSubscribers(room)).as("아직 b가 있다").isEqualTo(1);

        b.drop();
        awaitSubscribed(room, 0);
        assertThat(redisSubscribers(room)).isZero();
    }

    // ===== 토큰 만료 =====

    @Test
    @DisplayName("[E5] 연결 중 토큰이 만료되면 주기 작업이 ERROR TOKEN_EXPIRED를 보내고 연결을 끊는다")
    void expiredTokenIsDisconnected() throws Exception {
        LoggedIn me = users.create("나");
        long before = metrics.disconnects(ChatRealtimeMetrics.TOKEN_EXPIRED);
        RawStompClient c = RawStompClient.connect(port, tokenCookie(me.id(), Instant.now().plusSeconds(2)));
        clients.add(c);

        assertThat(tokenExpiryChecker.closeExpired(Instant.now())).as("아직 만료 전").isZero();
        assertThat(tokenExpiryChecker.closeExpired(Instant.now().plusSeconds(3))).isGreaterThanOrEqualTo(1);

        RawStompClient.Frame error = c.expect("ERROR", WAIT);
        assertThat(error.headers().get("message")).isEqualTo("TOKEN_EXPIRED");
        c.awaitClosed(WAIT);
        assertThat(metrics.disconnects(ChatRealtimeMetrics.TOKEN_EXPIRED) - before).isGreaterThanOrEqualTo(1);
    }

    // ===== 프레임 규칙 =====

    @Test
    @DisplayName("[4.4] 웹소켓 메시지 하나에 STOMP 프레임 여러 개를 담으면(대기열 상한 우회) 프로토콜 위반으로 끊는다")
    void multipleFramesPerMessageIsRejected() throws Exception {
        LoggedIn me = users.create("나");
        long room = chat.teamRoomId(me, teams.createTeam(me, "규칙 팀"));
        long before = metrics.disconnects(ChatRealtimeMetrics.PROTOCOL);
        RawStompClient c = connect(me);

        String one = RawStompClient.encode("SEND", Map.of("destination", "/app/rooms/" + room + "/messages"),
                "{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"content\":\"a\"}");
        c.sendText(one + one);

        c.awaitClosed(WAIT);
        assertThat(metrics.disconnects(ChatRealtimeMetrics.PROTOCOL) - before).isEqualTo(1);
        assertThat(chat.messageCount(room)).isZero();
    }

    // ===== 도우미 =====

    private Cookie tokenCookie(long userId, Instant expiresAt) {
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date())
                .expiration(Date.from(expiresAt))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
        return new Cookie(JwtCookie.NAME, token);
    }

    private void awaitSubscribed(long room, int sessions) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (roomSubscriptions.sessions(room).size() != sessions) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("방 구독 세션 수 " + roomSubscriptions.sessions(room).size() + " ≠ " + sessions);
            }
            Thread.sleep(20);
        }
    }

    /** Redis에서 본 그 방 채널의 구독 연결 수(PUBSUB NUMSUB). */
    private long redisSubscribers(long room) {
        return TestContainers.redisSubscribers(ChatDestinations.roomChannel(room).getTopic());
    }

    private long metricsDenied() {
        Counter c = meterRegistry.find("chat.subscribe.denied").counter();
        return c == null ? 0 : (long) c.count();
    }
}
