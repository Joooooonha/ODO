package SeSAC.Dance_Assessment.Service.Chat;

import SeSAC.Dance_Assessment.Realtime.ReadPublisher;
import SeSAC.Dance_Assessment.TestChat;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서버 쪽 읽음 모으기. 이 컨텍스트는 모으기를 켠다(받기 → 202, 500ms 주기로 처리).
 * 두 서버가 겹치는 행을 동시에 처리하는 경우는 같은 DB에 모으기 두 개(주기 없이 직접 처리)를 만들어 흉내 낸다.
 */
@SpringBootTest(properties = {"chat.read.aggregate.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ReadAggregatorTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    ChatMessageService chatMessageService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    ReadSnapshotReader reader;

    @Autowired
    ReadPublisher publisher;

    @Autowired
    ChatReadMetrics readMetrics;

    @Autowired
    org.springframework.data.redis.connection.RedisConnectionFactory redisFactory;

    TestUsers users;
    TestTeams teams;
    TestChat chat;

    @BeforeEach
    void setUp() {
        users = new TestUsers(mvc, om);
        teams = new TestTeams(mvc, om);
        chat = new TestChat(mvc, om, chatMessageService, jdbc);
    }

    private record Room(long id, LoggedIn leader, List<LoggedIn> members) {
        List<LoggedIn> all() {
            List<LoggedIn> out = new ArrayList<>(members);
            out.add(0, leader);
            return out;
        }
    }

    private Room room(int members, int messages) throws Exception {
        LoggedIn leader = users.create("팀장");
        List<LoggedIn> list = new ArrayList<>();
        for (int i = 0; i < members; i++) {
            list.add(users.create("팀원" + i));
        }
        long roomId = chat.teamRoomId(leader, teams.teamOf(leader, list.toArray(LoggedIn[]::new)));
        for (int i = 0; i < messages; i++) {
            chat.send(leader, roomId, "메시지 " + i);
        }
        return new Room(roomId, leader, list);
    }

    /** 주기 없이 flush()를 직접 부르는 모으기(다른 서버 한 대 몫) */
    private ReadAggregator manual(SimpleMeterRegistry registry) {
        return new ReadAggregator(false, 500, 0, 1, -1, jdbc, txManager, reader, publisher, readMetrics, registry);
    }

    private long readSeq(long roomId, long userId) {
        return (long) chat.member(roomId, userId)[1];
    }

    private static double count(SimpleMeterRegistry r, String name, String tag, String value) {
        var c = r.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("켜면 읽음 보고는 202(받았음)이고, 다음 처리 주기(≤ 500ms + 처리)에 반영된다. 남의 방은 403, seq 음수는 400")
    void acceptThenApplyOnCycle() throws Exception {
        Room r = room(1, 5);
        LoggedIn member = r.members().get(0);
        long last = chat.lastSeq(r.id());
        mvc.perform(post("/api/chat/rooms/{id}/read", r.id()).cookie(member.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"seq\":" + last + "}")).andExpect(status().isAccepted());

        long deadline = System.nanoTime() + WAIT.toNanos();
        while (readSeq(r.id(), member.id()) != last && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(readSeq(r.id(), member.id())).isEqualTo(last);

        LoggedIn stranger = users.create("남");
        mvc.perform(post("/api/chat/rooms/{id}/read", r.id()).cookie(stranger.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"seq\":1}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/rooms/{id}/read", r.id()).cookie(member.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"seq\":-1}")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("두 서버가 겹치는 행을 동시에 처리해도 교착이 없고 결과는 최대값이다(행을 방·사용자 순서로 잠근다). 중복은 stale로 센다")
    void twoServersOverlappingRowsNoDeadlockMaxWins() throws Exception {
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rooms.add(room(4, 60));
        }
        SimpleMeterRegistry ra = new SimpleMeterRegistry();
        SimpleMeterRegistry rb = new SimpleMeterRegistry();
        ReadAggregator a = manual(ra);
        ReadAggregator b = manual(rb);
        Map<String, Long> expected = new HashMap<>();
        Random random = new Random(42);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 60; round++) {
                // 같은 (방, 사용자)들을 두 서버가 다른 값으로 갖는다. 방 순서를 서로 반대로 넣어도 처리 순서는 정렬된다
                for (int ri = 0; ri < rooms.size(); ri++) {
                    Room ra2 = rooms.get(ri);
                    Room rb2 = rooms.get(rooms.size() - 1 - ri);
                    for (LoggedIn u : ra2.all()) {
                        long s = 1 + random.nextInt(60);
                        a.offer(ra2.id(), u.id(), s);
                        expected.merge(ra2.id() + ":" + u.id(), s, Math::max);
                    }
                    for (LoggedIn u : rb2.all()) {
                        long s = 1 + random.nextInt(60);
                        b.offer(rb2.id(), u.id(), s);
                        expected.merge(rb2.id() + ":" + u.id(), s, Math::max);
                    }
                }
                CountDownLatch go = new CountDownLatch(1);
                Future<Integer> fa = pool.submit(() -> {
                    go.await();
                    return a.flush();
                });
                Future<Integer> fb = pool.submit(() -> {
                    go.await();
                    return b.flush();
                });
                go.countDown();
                fa.get();
                fb.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(ra, "chat.read.agg.failures", "cause", "deadlock") + count(rb, "chat.read.agg.failures", "cause", "deadlock")).isZero();
        assertThat(count(ra, "chat.read.agg.failures", "cause", "other") + count(rb, "chat.read.agg.failures", "cause", "other")).isZero();
        assertThat(a.bufferSize() + b.bufferSize()).isZero();
        for (Room r : rooms) {
            for (LoggedIn u : r.all()) {
                assertThat(readSeq(r.id(), u.id())).as(r.id() + ":" + u.id()).isEqualTo(expected.get(r.id() + ":" + u.id()));
            }
        }
        assertThat(count(ra, "chat.read.agg.rows", "result", "stale") + count(rb, "chat.read.agg.rows", "result", "stale")).isPositive();
    }

    @Test
    @DisplayName("트랜잭션이 실패하면(잠금 시간 초과) 꺼낸 값을 버퍼에 되돌리고, 다음 주기에 반영한다")
    void failedCycleGivesBack() throws Exception {
        Room r = room(1, 5);
        LoggedIn member = r.members().get(0);
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ReadAggregator a = manual(reg);
        a.offer(r.id(), member.id(), 4);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            jdbc.queryForList("SELECT 1 FROM chat_room_member WHERE room_id = ? AND user_id = ? FOR UPDATE", r.id(), member.id());
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        locked.await();
        assertThat(a.flush()).isZero();   // 시간 제한 2초에 걸려 실패
        assertThat(a.bufferSize()).isEqualTo(1);
        assertThat(count(reg, "chat.read.agg.failures", "cause", "other")).isEqualTo(1);
        release.countDown();
        holder.join();
        a.offer(r.id(), member.id(), 3);   // 작은 값이 와도 큰 값이 남는다
        assertThat(a.flush()).isEqualTo(1);
        assertThat(readSeq(r.id(), member.id())).isEqualTo(4);
    }

    @Test
    @DisplayName("방의 마지막 번호보다 큰 값과 나간 멤버의 행은 처리할 때 버린다(갱신은 현재 ACTIVE 멤버의 행에만)")
    void outOfRangeAndLeftMemberAreDropped() throws Exception {
        Room r = room(2, 5);
        LoggedIn stays = r.members().get(0);
        LoggedIn left = r.members().get(1);
        long last = chat.lastSeq(r.id());
        long leftBefore = readSeq(r.id(), left.id());
        jdbc.update("UPDATE chat_room_member SET status = 'LEFT' WHERE room_id = ? AND user_id = ?", r.id(), left.id());
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ReadAggregator a = manual(reg);
        a.offer(r.id(), stays.id(), last + 10);
        a.offer(r.id(), left.id(), last);
        assertThat(a.flush()).isZero();
        assertThat(readSeq(r.id(), stays.id())).isLessThan(last + 10);
        assertThat(readSeq(r.id(), left.id())).isEqualTo(leftBefore);
        assertThat(count(reg, "chat.read.agg.rows", "result", "out_of_range")).isEqualTo(1);
        assertThat(count(reg, "chat.read.agg.rows", "result", "not_member")).isEqualTo(1);
    }

    @Test
    @DisplayName("측정 전용 '한 번 버리기'를 켜면 다음 주기의 값은 처리하지 않고 버린다(그다음 주기부터는 정상)")
    void dropOnce() throws Exception {
        Room r = room(1, 5);
        LoggedIn member = r.members().get(0);
        long before = readSeq(r.id(), member.id());
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ReadAggregator a = manual(reg);
        a.offer(r.id(), member.id(), 5);
        a.dropNextCycle();
        assertThat(a.flush()).isZero();
        assertThat(readSeq(r.id(), member.id())).isEqualTo(before);
        a.offer(r.id(), member.id(), 5);
        assertThat(a.flush()).isEqualTo(1);
        assertThat(readSeq(r.id(), member.id())).isEqualTo(5);
    }

    @Test
    @DisplayName("측정용 느린 주기 기록: 기준 이상인 주기만 단계별 시간(커넥션 획득·잠금·갱신·커밋·스냅샷·발행·그 밖)과 주기 번호를 남긴다")
    void slowCycleStages() throws Exception {
        Room r = room(1, 5);
        LoggedIn member = r.members().get(0);
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ReadAggregator a = manual(reg);
        a.offer(r.id(), member.id(), 2);
        assertThat(a.flush()).isEqualTo(1);   // 기준 100ms 아래 → 기록 없음
        assertThat(reg.find("chat.read.agg.slow.index").summary().count()).isZero();

        a.slowThresholdMs(0);
        a.offer(r.id(), member.id(), 3);
        assertThat(a.flush()).isEqualTo(1);
        assertThat(reg.find("chat.read.agg.slow.index").summary().count()).isEqualTo(1);
        for (String stage : List.of("write_acquire", "lock", "update", "commit", "snapshot_acquire", "snapshot_read", "publish", "other")) {
            assertThat(reg.find("chat.read.agg.slow.stage").tag("stage", stage).timer().count()).as(stage).isEqualTo(1);
        }
        assertThat(reg.find("chat.read.agg.slow.stage").tag("stage", "lock").timer().totalTime(TimeUnit.NANOSECONDS)).isPositive();
    }

    @Test
    @DisplayName("10/21 개선: 여러 방 스냅샷을 한 문장으로 읽어도 방마다 읽은 것과 같다(멤버가 없는 방도 한 줄, version은 한 문장이라 모두 같음)")
    void readAllMatchesPerRoom() throws Exception {
        Room a = room(2, 3);
        Room b = room(1, 2);
        Room empty = room(1, 1);
        jdbc.update("UPDATE chat_room_member SET status = 'LEFT' WHERE room_id = ?", empty.id());
        List<ReadSnapshot> all = new TransactionTemplate(txManager).execute(st -> reader.readAll(List.of(b.id(), empty.id(), a.id(), b.id())));
        assertThat(all).extracting(ReadSnapshot::roomId).containsExactly(
                java.util.stream.Stream.of(a.id(), b.id(), empty.id()).sorted().toArray(Long[]::new));
        for (ReadSnapshot s : all) {
            assertThat(s.positions()).isEqualTo(reader.read(s.roomId()).positions());
            assertThat(s.version()).isEqualTo(all.get(0).version());
        }
        assertThat(all.stream().filter(s -> s.roomId() == empty.id()).findFirst().orElseThrow().positions()).isEmpty();
    }

    @Test
    @DisplayName("10/21 개선: 한 주기의 발행을 파이프라인 한 번으로 해도 바뀐 방마다 READ가 하나씩 Redis 방 채널에 나간다")
    void pipelinedPublishReachesEveryRoom() throws Exception {
        List<Room> rooms = List.of(room(1, 3), room(1, 3), room(1, 3));
        Map<String, Integer> got = new java.util.concurrent.ConcurrentHashMap<>();
        var container = new org.springframework.data.redis.listener.RedisMessageListenerContainer();
        container.setConnectionFactory(redisFactory);
        for (Room r : rooms) {
            container.addMessageListener((message, pattern) -> got.merge(new String(message.getChannel()), 1, Integer::sum),
                    SeSAC.Dance_Assessment.Realtime.ChatDestinations.roomChannel(r.id()));
        }
        container.afterPropertiesSet();
        container.start();
        try {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (!container.isListening() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(200);   // 구독 등록이 Redis에 닿을 때까지
            SimpleMeterRegistry reg = new SimpleMeterRegistry();
            ReadAggregator agg = manual(reg);
            for (Room r : rooms) {
                agg.offer(r.id(), r.members().get(0).id(), 2);
            }
            assertThat(agg.flush()).isEqualTo(3);
            deadline = System.nanoTime() + WAIT.toNanos();
            while (got.size() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            for (Room r : rooms) {
                assertThat(got.get(SeSAC.Dance_Assessment.Realtime.ChatDestinations.roomChannel(r.id()).getTopic())).isEqualTo(1);
            }
            assertThat(reg.counter("chat.read.agg.publishes").count()).isEqualTo(3);
        } finally {
            container.stop();
            container.destroy();
        }
    }

    @Test
    @DisplayName("처리 위상: 서버 순번 i, 서버 수 N이면 i × 500 / N ms, 덮어쓰기 값이 있으면 그 값")
    void phase() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        assertThat(new ReadAggregator(false, 500, 1, 2, -1, jdbc, txManager, reader, publisher, readMetrics, reg).phaseMs()).isEqualTo(250);
        assertThat(new ReadAggregator(false, 500, 0, 2, -1, jdbc, txManager, reader, publisher, readMetrics, new SimpleMeterRegistry()).phaseMs()).isZero();
        assertThat(new ReadAggregator(false, 500, 1, 2, 0, jdbc, txManager, reader, publisher, readMetrics, new SimpleMeterRegistry()).phaseMs()).isZero();
    }
}
