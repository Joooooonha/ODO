package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.Service.Chat.ChatSendError;
import SeSAC.Dance_Assessment.Service.Chat.ChatSendMetrics;
import SeSAC.Dance_Assessment.Service.Chat.SendOutcome;
import SeSAC.Dance_Assessment.TestChat;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 불변식 동시성 테스트(11.4). <b>진짜 PostgreSQL에서 여러 스레드로 동시에</b> 실행한다 —
 * H2는 잠금 동작이 달라 통과해도 의미가 없다.
 *
 * <p>모든 작업은 출발선(latch)에서 함께 출발한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatConcurrencyTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    ChatMessageService chatMessageService;

    @Autowired
    ChatSendMetrics metrics;

    @Autowired
    JdbcTemplate jdbc;

    TestUsers users;
    TestTeams teams;
    TestChat chat;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        users = new TestUsers(mvc, om);
        teams = new TestTeams(mvc, om);
        chat = new TestChat(mvc, om, chatMessageService, jdbc);
        // 출발선에서 모든 작업이 동시에 기다려야 하므로 작업 수만큼 스레드가 있어야 한다.
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("같은 방에 100개 동시 전송 → seq 1..N 빈틈·중복 없음, 전역 불변식 쿼리 0건, seq 순서와 시각 순서가 어긋나지 않음")
    void sameRoomConcurrentSends() throws Exception {
        LoggedIn leader = users.create("팀장");
        List<LoggedIn> senders = new ArrayList<>(List.of(leader));
        for (int i = 0; i < 4; i++) {
            senders.add(users.create("팀원" + i));
        }
        long room = chat.teamRoomId(leader, teams.teamOf(leader, senders.subList(1, 5).toArray(LoggedIn[]::new)));
        long before = chat.lastSeq(room);

        List<Callable<SendOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            LoggedIn sender = senders.get(i % senders.size());
            int n = i;
            tasks.add(() -> chat.trySend(sender, room, UUID.randomUUID(), "동시 " + n));
        }
        List<SendOutcome> outcomes = runTogether(tasks);

        assertThat(outcomes).allMatch(o -> o instanceof SendOutcome.Saved);
        assertThat(chat.lastSeq(room)).isEqualTo(before + 100);
        chat.assertContiguous(room);
        assertThat(chat.roomsWithGaps()).isEmpty();

        List<OffsetDateTime> times = jdbc.queryForList(
                "SELECT created_at FROM message WHERE room_id = ? ORDER BY seq", OffsetDateTime.class, room);
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).as("seq %d의 시각", i + 1).isAfterOrEqualTo(times.get(i - 1));
        }
    }

    @Test
    @DisplayName("여러 방에 동시 전송 → 방마다 독립적으로 1..N")
    void manyRoomsConcurrentSends() throws Exception {
        List<LoggedIn> owners = new ArrayList<>();
        List<Long> rooms = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            LoggedIn owner = users.create("방장" + i);
            owners.add(owner);
            rooms.add(chat.teamRoomId(owner, teams.createTeam(owner, "방 " + i)));
        }

        List<Callable<SendOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            int r = i % 4;
            tasks.add(() -> chat.trySend(owners.get(r), rooms.get(r), UUID.randomUUID(), "m"));
        }
        assertThat(runTogether(tasks)).allMatch(o -> o instanceof SendOutcome.Saved);

        for (long room : rooms) {
            assertThat(chat.lastSeq(room)).isEqualTo(40);
            chat.assertContiguous(room);
        }
    }

    @Test
    @DisplayName("같은 clientMessageId 동시 2회(10번 반복) → 행 1개, 두 응답의 seq 동일")
    void sameClientMessageIdTwice() throws Exception {
        LoggedIn me = users.create("나");
        long room = chat.teamRoomId(me, teams.createTeam(me, "중복 팀"));

        for (int round = 0; round < 10; round++) {
            UUID id = UUID.randomUUID();
            List<SendOutcome> outcomes = runTogether(List.of(
                    () -> chat.trySend(me, room, id, "같은 메시지"),
                    () -> chat.trySend(me, room, id, "같은 메시지")));

            assertThat(outcomes).allMatch(o -> o instanceof SendOutcome.Saved);
            long seq0 = ((SendOutcome.Saved) outcomes.get(0)).message().seq();
            long seq1 = ((SendOutcome.Saved) outcomes.get(1)).message().seq();
            assertThat(seq0).isEqualTo(seq1);
            assertThat(outcomes).filteredOn(o -> ((SendOutcome.Saved) o).duplicate()).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE client_message_id = ?", Long.class, id))
                    .isEqualTo(1);
        }
        chat.assertContiguous(room);
    }

    @Test
    @DisplayName("[H1] 같은 두 사람이 서로 동시에 DM을 열어도(10쌍) 방은 하나, 두 응답의 방 id가 같다")
    void dmBothDirections() throws Exception {
        for (int round = 0; round < 10; round++) {
            LoggedIn a = users.create("가" + round);
            LoggedIn b = users.create("나" + round);
            teams.teamOf(a, b);

            List<Long> ids = runTogether(List.of(() -> chat.openDm(a, b), () -> chat.openDm(b, a)));

            assertThat(ids.get(0)).isEqualTo(ids.get(1));
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM chat_room WHERE dm_user_low = ? AND dm_user_high = ?", Long.class,
                    Math.min(a.id(), b.id()), Math.max(a.id(), b.id()))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_room_member WHERE room_id = ?",
                    Long.class, ids.get(0))).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("[D4] 추방과 전송 경쟁(5번 반복) → 추방 커밋 이후 번호를 가진 그 사람의 메시지 0건")
    void kickRacesWithSend() throws Exception {
        for (int round = 0; round < 5; round++) {
            LoggedIn leader = users.create("팀장");
            LoggedIn member = users.create("팀원");
            Long teamId = teams.teamOf(leader, member);
            long room = chat.teamRoomId(leader, teamId);

            CountDownLatch sending = new CountDownLatch(1);
            Future<SendOutcome> sender = pool.submit(() -> {
                SendOutcome last = null;
                for (int i = 0; i < 2000; i++) {
                    last = chat.trySend(member, room, UUID.randomUUID(), "경쟁 " + i);
                    sending.countDown();
                    if (last instanceof SendOutcome.Rejected) {
                        break;
                    }
                }
                return last;
            });
            sending.await(10, TimeUnit.SECONDS);
            teams.kick(leader, teamId, member);

            assertThat(sender.get(30, TimeUnit.SECONDS))
                    .isEqualTo(new SendOutcome.Rejected(ChatSendError.NOT_MEMBER));
            // 추방 안내는 추방 커밋 뒤에 저장된다. 그 사람의 메시지가 전부 그보다 앞이어야 한다.
            assertThat(chat.maxTextSeq(room, member.id()))
                    .isLessThan(chat.systemSeq(room, "KICK", member.id()));
            chat.assertContiguous(room);
        }
    }

    @Test
    @DisplayName("같은 방에 동시 전송하는 동안 참여·나가기·재가입·추방이 섞여도 교착(40P01) 재시도 0건, 소진 0건")
    void noDeadlockUnderMixedLoad() throws Exception {
        LoggedIn leader = users.create("팀장");
        List<LoggedIn> talkers = List.of(users.create("말1"), users.create("말2"), users.create("말3"));
        List<LoggedIn> movers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            movers.add(users.create("이동" + i));
        }
        Long teamId = teams.teamOf(leader, talkers.toArray(LoggedIn[]::new));
        long room = chat.teamRoomId(leader, teamId);
        String code = teams.joinCode(leader, teamId);
        long deadlocksBefore = metrics.retries("40P01");
        long exhaustedBefore = metrics.exhaustedCount();

        List<Callable<Object>> tasks = new ArrayList<>();
        for (LoggedIn talker : talkers) {
            tasks.add(() -> {
                for (int i = 0; i < 40; i++) {
                    chat.send(talker, room, "말 " + i);
                }
                return null;
            });
        }
        for (int i = 0; i < movers.size(); i++) {
            LoggedIn mover = movers.get(i);
            boolean kickAtEnd = i % 2 == 0;
            tasks.add(() -> {
                teams.rejoin(mover, code);
                chat.send(mover, room, "들어왔다");
                teams.leave(mover, teamId);
                teams.rejoin(mover, code);
                if (kickAtEnd) {
                    teams.kick(leader, teamId, mover);
                }
                return null;
            });
        }
        runTogether(tasks);

        assertThat(metrics.retries("40P01") - deadlocksBefore).as("교착으로 인한 재시도").isZero();
        assertThat(metrics.exhaustedCount() - exhaustedBefore).isZero();
        chat.assertContiguous(room);
        assertThat(chat.roomsWithGaps()).isEmpty();
    }

    /** 모든 작업을 출발선에서 함께 출발시키고, 결과를 작업 순서대로 돌려준다. 예외는 그대로 다시 던진다. */
    private <T> List<T> runTogether(List<? extends Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(120, TimeUnit.SECONDS));
        }
        return results;
    }
}
