package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.RawStompClient;
import SeSAC.Dance_Assessment.Realtime.ChatDestinations;
import SeSAC.Dance_Assessment.Realtime.ChatRealtimeMetrics;
import SeSAC.Dance_Assessment.Realtime.RoomSubscriptions;
import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.Service.Chat.ChatReadMetrics;
import SeSAC.Dance_Assessment.Service.Chat.ReadRanges;
import SeSAC.Dance_Assessment.Service.Chat.ReadSnapshot;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 읽음 표시 기준선(Task-chat-read 3절, 9.1) — 한 서버 안. 서버 2대는 {@link ChatTwoServerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatReadTest {

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
    ChatRealtimeMetrics realtimeMetrics;

    @Autowired
    ChatReadMetrics readMetrics;

    @Autowired
    RoomSubscriptions roomSubscriptions;

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

    // ===== 준비 =====

    /** 팀장 + 팀원들, 메시지 n개를 보낸 팀 방. */
    private record Room(long id, LoggedIn leader, List<LoggedIn> members, Long teamId) {
    }

    private Room room(int members, int messages) throws Exception {
        LoggedIn leader = users.create("팀장");
        List<LoggedIn> list = new ArrayList<>();
        for (int i = 0; i < members; i++) {
            list.add(users.create("팀원" + i));
        }
        Long teamId = teams.teamOf(leader, list.toArray(LoggedIn[]::new));
        long roomId = chat.teamRoomId(leader, teamId);
        for (int i = 0; i < messages; i++) {
            chat.send(leader, roomId, "메시지 " + i);
        }
        return new Room(roomId, leader, list, teamId);
    }

    private ResultActions report(LoggedIn user, long roomId, Object body) throws Exception {
        return mvc.perform(post("/api/chat/rooms/{id}/read", roomId).cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private void read(LoggedIn user, long roomId, long seq) throws Exception {
        report(user, roomId, Map.of("seq", seq)).andExpect(status().isNoContent());
    }

    private long readSeq(long roomId, long userId) {
        return (long) chat.member(roomId, userId)[1];
    }

    private RawStompClient connect(LoggedIn user, long room) throws Exception {
        RawStompClient c = RawStompClient.connect(port, user.cookie());
        clients.add(c);
        c.subscribe(ChatDestinations.roomTopic(room));
        c.awaitSubscriptionsProcessed();
        return c;
    }

    private static Predicate<RawStompClient.Frame> readFrame(long room) {
        return f -> "MESSAGE".equals(f.command()) && ChatDestinations.roomTopic(room).equals(f.destination())
                && f.body().contains("\"type\":\"READ\"");
    }

    private JsonNode nextRead(RawStompClient c, long room) throws InterruptedException {
        return c.expect(readFrame(room), WAIT).json(om);
    }

    private static long positionOf(JsonNode frame, long userId) {
        for (JsonNode p : frame.get("positions")) {
            if (p.get("userId").asLong() == userId) {
                return p.get("lastReadSeq").asLong();
            }
        }
        throw new AssertionError("위치 없음 userId=" + userId + " frame=" + frame);
    }

    // ===== 3.1 보고 =====

    @Test
    @DisplayName("[9.1] 본문의 사용자 정보는 무시되고 인증 사용자(쿠키)의 위치만 바뀐다")
    void bodyUserIsIgnored() throws Exception {
        Room r = room(1, 3);
        LoggedIn member = r.members().get(0);
        long leaderBefore = readSeq(r.id(), r.leader().id());
        long last = chat.lastSeq(r.id());

        Map<String, Object> body = new HashMap<>();
        body.put("seq", last);
        body.put("userId", r.leader().id());   // 남의 위치를 바꾸려는 시도
        report(member, r.id(), body).andExpect(status().isNoContent());

        assertThat(readSeq(r.id(), member.id())).isEqualTo(last);
        assertThat(readSeq(r.id(), r.leader().id())).as("남의 위치는 그대로").isEqualTo(leaderBefore);
    }

    @Test
    @DisplayName("[9.1·A5] 역행 없음: 큰 값 → 작은 값 순서로 보고하면 큰 값 유지, 두 번째는 noop이고 스냅샷·발행이 없다")
    void noRegressionAndNoopPublishesNothing() throws Exception {
        Room r = room(1, 5);
        LoggedIn member = r.members().get(0);
        long last = chat.lastSeq(r.id());

        read(member, r.id(), last);
        long published = realtimeMetrics.publishes(ChatRealtimeMetrics.READ);
        long noop = readMetrics.requests(ChatReadMetrics.NOOP);

        read(member, r.id(), last - 2);   // 늦게 도착한 PC의 작은 값
        read(member, r.id(), last);       // 같은 값

        assertThat(readSeq(r.id(), member.id())).isEqualTo(last);
        assertThat(readMetrics.requests(ChatReadMetrics.NOOP) - noop).isEqualTo(2);
        assertThat(realtimeMetrics.publishes(ChatRealtimeMetrics.READ)).as("noop은 발행하지 않는다").isEqualTo(published);
    }

    @Test
    @DisplayName("[9.1] 두 기기 동시 보고(여러 스레드, 서로 다른 값) → 최종값 = 최댓값")
    void concurrentReportsKeepMaximum() throws Exception {
        Room r = room(1, 30);
        LoggedIn member = r.members().get(0);
        long last = chat.lastSeq(r.id());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (long seq = last; seq >= last - 15; seq--) {
            long s = seq;
            results.add(pool.submit(() -> {
                start.await();
                return report(member, r.id(), Map.of("seq", s)).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> f : results) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(204);
        }
        pool.shutdown();

        assertThat(readSeq(r.id(), member.id())).isEqualTo(last);
    }

    @Test
    @DisplayName("[회귀] 커넥션 풀(10)보다 많은 동시 읽음 보고도 막히지 않는다 — 요청 하나가 커넥션 하나만 쓴다(OSIV에서 갱신 커넥션을 쥔 채 스냅샷이 두 번째를 기다리던 문제)")
    void concurrentReportsBeyondPoolSizeDoNotStarve() throws Exception {
        Room r = room(24, 3);
        long last = chat.lastSeq(r.id());
        List<LoggedIn> readers = new ArrayList<>(r.members());
        readers.add(r.leader());

        ExecutorService pool = Executors.newFixedThreadPool(readers.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (LoggedIn reader : readers) {
            results.add(pool.submit(() -> {
                start.await();
                return report(reader, r.id(), Map.of("seq", last)).andReturn().getResponse().getStatus();
            }));
        }
        long started = System.nanoTime();
        start.countDown();
        for (Future<Integer> f : results) {
            assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(204);
        }
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        pool.shutdown();
        assertThat(millis).as("커넥션 획득 시간 제한(30초)에 걸리지 않는다").isLessThan(10_000);
    }

    @Test
    @DisplayName("[9.1] last_seq보다 큰 seq → 400, seq 없음·음수 → 400, 멤버 아님(방 없음 포함) → 403, 나간 멤버 → 403")
    void validation() throws Exception {
        Room r = room(1, 2);
        LoggedIn member = r.members().get(0);
        LoggedIn outsider = users.create("남");
        long last = chat.lastSeq(r.id());

        report(member, r.id(), Map.of("seq", last + 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READ_SEQ_AHEAD"));
        report(member, r.id(), Map.of("seq", -1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_READ_SEQ"));
        report(member, r.id(), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_READ_SEQ"));
        report(outsider, r.id(), Map.of("seq", 1)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_CHAT_MEMBER"));
        // 멤버가 아니면 seq가 커도 400이 아니라 403(방이 있는지·몇 번까지 있는지 알려 주지 않는다)
        report(outsider, r.id(), Map.of("seq", last + 100)).andExpect(status().isForbidden());
        report(outsider, Long.MAX_VALUE, Map.of("seq", 1)).andExpect(status().isForbidden());

        teams.leave(member, r.teamId());
        report(member, r.id(), Map.of("seq", 1)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_CHAT_MEMBER"));
    }

    @Test
    @DisplayName("[9.1·3.1] 읽음 갱신은 modified_at을 바꾸지 않고, 5초 재확인의 보정 지표도 늘지 않는다")
    void readDoesNotTouchModifiedAt() throws Exception {
        Room r = room(1, 3);
        LoggedIn member = r.members().get(0);
        connect(member, r.id());
        OffsetDateTime before = modifiedAt(r.id(), member.id());
        long revoked = realtimeMetrics.recheckCorrections("REVOKED");
        long granted = realtimeMetrics.recheckCorrections("GRANTED");

        for (long seq = 1; seq <= chat.lastSeq(r.id()); seq++) {
            read(member, r.id(), seq);
        }
        Thread.sleep(6_000);   // 재확인 한 바퀴(5초) 이상

        assertThat(modifiedAt(r.id(), member.id())).isEqualTo(before);
        assertThat(realtimeMetrics.recheckCorrections("REVOKED")).isEqualTo(revoked);
        assertThat(realtimeMetrics.recheckCorrections("GRANTED")).isEqualTo(granted);
    }

    private OffsetDateTime modifiedAt(long roomId, long userId) {
        return jdbc.queryForObject("SELECT modified_at FROM chat_room_member WHERE room_id = ? AND user_id = ?",
                OffsetDateTime.class, roomId, userId);
    }

    // ===== 3.2·3.3 스냅샷·구간 =====

    @Test
    @DisplayName("[9.1·3.3] 위치가 올라가면 그 방 구독 세션 전원(보고한 사람 포함)이 READ를 받고, 구간이 위치와 맞다")
    void advancedReportIsBroadcastWithRanges() throws Exception {
        Room r = room(2, 4);
        LoggedIn a = r.members().get(0);
        LoggedIn b = r.members().get(1);
        RawStompClient leaderWs = connect(r.leader(), r.id());
        RawStompClient aWs = connect(a, r.id());
        long last = chat.lastSeq(r.id());
        long frames = realtimeMetrics.outboundFrames(ChatRealtimeMetrics.READ);

        read(a, r.id(), last);

        RawStompClient.Frame raw = leaderWs.expect(readFrame(r.id()), WAIT);
        assertThat(raw.headers()).as("종류 헤더는 서버 안에서만 쓰고 브라우저로 나가지 않는다")
                .doesNotContainKey(SeSAC.Dance_Assessment.Realtime.RoomChannelListener.FRAME_TYPE_HEADER);
        JsonNode f1 = raw.json(om);
        JsonNode f2 = nextRead(aWs, r.id());
        assertThat(f1.get("version").asLong()).isEqualTo(f2.get("version").asLong());
        assertThat(positionOf(f1, a.id())).isEqualTo(last);
        List<ReadRanges.Range> ranges = om.convertValue(f1.get("ranges"),
                om.getTypeFactory().constructCollectionType(List.class, ReadRanges.Range.class));
        List<ReadSnapshot.Position> positions = om.convertValue(f1.get("positions"),
                om.getTypeFactory().constructCollectionType(List.class, ReadSnapshot.Position.class));
        assertThat(positions).hasSize(3);
        for (long s = 1; s <= last; s++) {
            long seq = s;
            long expected = positions.stream().filter(p -> p.lastReadSeq() < seq).count();
            assertThat(ReadRanges.unreadAt(ranges, s)).as("seq %d", s).isEqualTo((int) expected);
        }
        assertThat(ReadRanges.unreadAt(ranges, last)).as("마지막 메시지: a만 읽음 → 팀장·b 2명").isEqualTo(2);
        assertThat(positionOf(f1, b.id())).isLessThan(last);
        assertThat(realtimeMetrics.outboundFrames(ChatRealtimeMetrics.READ) - frames).isEqualTo(2);
    }

    @Test
    @DisplayName("[9.1] 연달아 두 번 갱신하면 뒤 스냅샷의 version이 크고, 모든 사용자 위치가 같거나 크다")
    void laterSnapshotHasLargerVersion() throws Exception {
        Room r = room(2, 6);
        LoggedIn a = r.members().get(0);
        LoggedIn b = r.members().get(1);
        RawStompClient ws = connect(r.leader(), r.id());
        long last = chat.lastSeq(r.id());

        read(a, r.id(), last - 1);
        read(b, r.id(), last);

        JsonNode first = nextRead(ws, r.id());
        JsonNode second = nextRead(ws, r.id());
        assertThat(second.get("version").asLong()).isGreaterThan(first.get("version").asLong());
        for (JsonNode p : first.get("positions")) {
            long user = p.get("userId").asLong();
            assertThat(positionOf(second, user)).isGreaterThanOrEqualTo(p.get("lastReadSeq").asLong());
        }
    }

    @Test
    @DisplayName("[9.1·6.2] 추방된 사람에게는 그 방의 READ 프레임이 가지 않는다(나가는 메시지 권한 검사)")
    void kickedMemberGetsNoReadFrames() throws Exception {
        Room r = room(2, 3);
        LoggedIn kicked = r.members().get(0);
        LoggedIn stays = r.members().get(1);
        RawStompClient kickedWs = connect(kicked, r.id());
        RawStompClient staysWs = connect(stays, r.id());

        teams.kick(r.leader(), r.teamId(), kicked);
        assertThat(kickedWs.nextEvent(WAIT).get("type").asText()).isEqualTo("REVOKED");

        read(r.leader(), r.id(), chat.lastSeq(r.id()));
        nextRead(staysWs, r.id());
        kickedWs.expectNone(readFrame(r.id()), Duration.ofSeconds(1));
    }

    // ===== 3.4 read-state =====

    @Test
    @DisplayName("[9.1·3.4] read-state: 멤버 아니면 403, 응답은 방송과 같은 계산 결과")
    void readStateMatchesBroadcast() throws Exception {
        Room r = room(2, 4);
        LoggedIn a = r.members().get(0);
        LoggedIn outsider = users.create("남");
        RawStompClient ws = connect(r.leader(), r.id());

        mvc.perform(get("/api/chat/rooms/{id}/read-state", r.id()).cookie(outsider.cookie()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_CHAT_MEMBER"));

        read(a, r.id(), chat.lastSeq(r.id()) - 1);
        JsonNode broadcast = nextRead(ws, r.id());
        JsonNode state = om.readTree(mvc.perform(get("/api/chat/rooms/{id}/read-state", r.id()).cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(state.get("version").asLong()).isGreaterThan(broadcast.get("version").asLong());
        assertThat(state.get("positions")).isEqualTo(broadcast.get("positions"));
        assertThat(state.get("ranges")).isEqualTo(broadcast.get("ranges"));
    }

    @Test
    @DisplayName("[3.2] 스냅샷은 활동 멤버만 — 나간 멤버는 positions에 없다")
    void snapshotExcludesLeftMembers() throws Exception {
        Room r = room(2, 2);
        LoggedIn left = r.members().get(0);
        teams.leave(left, r.teamId());

        JsonNode state = om.readTree(mvc.perform(get("/api/chat/rooms/{id}/read-state", r.id())
                        .cookie(r.leader().cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        state.get("positions").forEach(p -> ids.add(p.get("userId").asLong()));
        assertThat(ids).containsExactlyInAnyOrder(r.leader().id(), r.members().get(1).id());
    }
}
