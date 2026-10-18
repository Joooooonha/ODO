package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.Dto.Chat.ChatSendRequest;
import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.Service.Chat.ChatReply;
import SeSAC.Dance_Assessment.Service.Chat.ChatRoomService;
import SeSAC.Dance_Assessment.Service.Chat.ChatSendError;
import SeSAC.Dance_Assessment.Service.Chat.ChatSendHandler;
import SeSAC.Dance_Assessment.Service.Chat.ChatSendMetrics;
import SeSAC.Dance_Assessment.Service.Chat.ChatSendSession;
import SeSAC.Dance_Assessment.Service.Chat.MessageWriter;
import SeSAC.Dance_Assessment.Service.Chat.SendOutcome;
import SeSAC.Dance_Assessment.TestChat;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장애를 주입해야 확인할 수 있는 것들: 채팅방 생성 실패 시 팀 롤백(7.1), 안내 저장 실패와 팀 처리(E2),
 * 재시도·소진(4.2), 세션 멈춤·재개와 보낸 순서(4.3, 11.4), 분류되지 않은 예외(4.6).
 *
 * <p>스파이 빈 때문에 이 클래스는 컨텍스트를 따로 쓴다. 스파이는 테스트마다 초기화된다.
 *
 * <p><b>주의: 스파이는 트랜잭션 프록시 안쪽(실제 객체)을 감싼다.</b> 스파이에서 던진 예외는 저장
 * 트랜잭션 안에서 터져 롤백된다. "커밋은 됐는데 호출한 쪽은 실패를 받은" 상황은
 * {@link #commitThenFail}처럼 실제 저장을 별도 트랜잭션으로 먼저 커밋해야 만들 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatFaultInjectionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    ChatMessageService chatMessageService;

    @Autowired
    ChatSendHandler handler;

    @Autowired
    ChatSendMetrics metrics;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    MessageWriter writer;

    @MockitoSpyBean
    ChatRoomService chatRoomService;

    TestUsers users;
    TestTeams teams;
    TestChat chat;
    LoggedIn me;
    long room;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(mvc, om);
        teams = new TestTeams(mvc, om);
        chat = new TestChat(mvc, om, chatMessageService, jdbc);
        me = users.create("나");
        room = chat.teamRoomId(me, teams.createTeam(me, "장애 팀"));
    }

    /** 재시도 대상인 실패 — 트랜잭션 시간 제한으로 PostgreSQL이 문장을 취소한 경우. */
    private static QueryTimeoutException statementTimeout() {
        return new QueryTimeoutException("주입: 문장 시간 초과",
                new SQLException("canceling statement due to statement timeout", "57014"));
    }

    /**
     * 실제 저장을 별도 트랜잭션으로 <b>커밋한 뒤</b> 재시도 대상 예외를 던진다 — COMMIT 직후 연결이
     * 끊겨 결과를 모르는 경우. 바깥(프록시) 트랜잭션에는 아무 문장도 없으니 롤백해도 지울 것이 없다.
     */
    private Object commitThenFail(org.mockito.invocation.InvocationOnMock inv) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.execute(status -> {
            try {
                return inv.callRealMethod();
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        });
        throw statementTimeout();
    }

    // ===== 팀 연결 =====

    @Test
    @DisplayName("[7.1] 채팅방 생성을 강제로 실패시키면 팀도 생성되지 않는다(같은 트랜잭션)")
    void roomFailureRollsBackTeam() throws Exception {
        doThrow(new IllegalStateException("주입: 채팅방 생성 실패"))
                .when(chatRoomService).createTeamRoom(any(), any());
        String name = "롤백 팀 " + UUID.randomUUID().toString().substring(0, 6);

        mvc.perform(post("/api/teams").cookie(me.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("name", name))))
                .andExpect(status().is4xxClientError());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM team WHERE team_name = ?", Long.class, name)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM team_member m JOIN team t ON t.team_id = m.team_id WHERE t.team_name = ?",
                Long.class, name)).isZero();
    }

    @Test
    @DisplayName("[E2] 안내 메시지 저장을 강제로 실패시켜도 팀 참여·나가기는 성공하고 채팅 멤버 상태도 바뀐다")
    void systemMessageFailureKeepsTeamResult() throws Exception {
        doThrow(new IllegalStateException("주입: 안내 저장 실패"))
                .when(writer).writeSystem(anyLong(), any(), anyString());
        LoggedIn member = users.create("팀원");
        Long teamId = jdbc.queryForObject("SELECT team_id FROM chat_room WHERE room_id = ?", Long.class, room);

        teams.rejoin(member, teams.joinCode(me, teamId));
        assertThat(chat.member(room, member.id())[0]).isEqualTo("ACTIVE");
        teams.leave(member, teamId);
        assertThat(chat.member(room, member.id())[0]).isEqualTo("LEFT");
        assertThat(chat.systemContents(room)).isEmpty();
        assertThat(chat.lastSeq(room)).as("실패한 안내는 번호를 쓰지 않는다").isZero();
    }

    @Test
    @DisplayName("[E2] 커밋은 됐는데 결과를 못 받아 같은 사건을 다시 저장해도 안내는 1건 (부분 유일 인덱스)")
    void systemRetryAfterCommitIsOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> calls.incrementAndGet() == 1 ? commitThenFail(inv) : inv.callRealMethod())
                .when(writer).writeSystem(anyLong(), any(), anyString());
        LoggedIn member = users.create("팀원");
        Long teamId = jdbc.queryForObject("SELECT team_id FROM chat_room WHERE room_id = ?", Long.class, room);
        long duplicatesBefore = metrics.duplicateCount();

        teams.rejoin(member, teams.joinCode(me, teamId));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(metrics.duplicateCount() - duplicatesBefore)
                .as("두 번째 저장은 이미 커밋된 안내를 만나 중복으로 걸렀다").isEqualTo(1);
        assertThat(chat.systemContents(room)).hasSize(1);
        chat.assertContiguous(room);
    }

    // ===== 재시도 =====

    @Test
    @DisplayName("[4.2] 재시도 가능한 오류 2번 뒤 성공한다. 번호는 빈틈 없고 재시도 지표가 원인(57014)별로 남는다")
    void retryThenSucceed() {
        long before = metrics.retries("57014");
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() <= 2) {
                throw statementTimeout();
            }
            return inv.callRealMethod();
        }).when(writer).writeText(anyLong(), anyLong(), any(), anyString());

        SendOutcome outcome = chat.trySend(me, room, UUID.randomUUID(), "세 번째에 성공");

        assertThat(outcome).isInstanceOf(SendOutcome.Saved.class);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(metrics.retries("57014") - before).isEqualTo(2);
        chat.assertContiguous(room);
    }

    @Test
    @DisplayName("[4.2] 커밋된 뒤 결과를 못 받아 재시도해도 새 행 없이 기존 번호로 성공한다")
    void retryAfterCommitIsDuplicate() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> calls.incrementAndGet() == 1 ? commitThenFail(inv) : inv.callRealMethod())
                .when(writer).writeText(anyLong(), anyLong(), any(), anyString());

        SendOutcome outcome = chat.trySend(me, room, UUID.randomUUID(), "한 번만 저장");

        assertThat(outcome).isInstanceOf(SendOutcome.Saved.class);
        assertThat(((SendOutcome.Saved) outcome).duplicate()).isTrue();
        assertThat(chat.messageCount(room)).isEqualTo(1);
        chat.assertContiguous(room);
    }

    @Test
    @DisplayName("[4.2·4.3] 재시도가 끝까지 실패하면 최대 4번 시도 후 SERVER_RETRY_EXHAUSTED 1건 + 세션 멈춤, 행 없음")
    void exhaustionHaltsSession() {
        doThrow(statementTimeout()).when(writer).writeText(anyLong(), anyLong(), any(), anyString());
        ChatSendSession session = new ChatSendSession();
        String id = UUID.randomUUID().toString();

        ChatReply reply = handler.handle(session, me.id(), room, new ChatSendRequest(id, "실패", null));

        assertThat(reply).isEqualTo(new ChatReply.Error(room, id, ChatSendError.SERVER_RETRY_EXHAUSTED));
        assertThat(session.isHalted()).isTrue();
        assertThat(chat.messageCount(room)).isZero();
        assertThat(chat.lastSeq(room)).isZero();
    }

    // ===== 세션 멈춤·재개와 보낸 순서 =====

    @Test
    @DisplayName("[4.3·11.4] 같은 세션에서 연달아 보낸 메시지 — 중간에 재시도 소진 → 멈춘 동안 온 것은 응답 없이 버림 → resume으로 다시 보내면 저장 순서 = 보낸 순서")
    void haltResumeKeepsOrder() {
        UUID failing = UUID.randomUUID();
        AtomicInteger failures = new AtomicInteger();
        doAnswer(inv -> {
            if (inv.getArgument(2).equals(failing) && failures.incrementAndGet() <= 4) {
                throw statementTimeout();     // 4번 모두 실패 → 소진. 재개 때는 정상
            }
            return inv.callRealMethod();
        }).when(writer).writeText(anyLong(), anyLong(), any(), anyString());

        ChatSendSession session = new ChatSendSession();
        List<String> sent = List.of(UUID.randomUUID().toString(), failing.toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString());

        assertThat(send(session, sent.get(0), false)).isInstanceOf(ChatReply.Ack.class);
        assertThat(send(session, sent.get(1), false))
                .isEqualTo(new ChatReply.Error(room, sent.get(1), ChatSendError.SERVER_RETRY_EXHAUSTED));
        // 실패 순간 대기열·네트워크에 있던 뒤 메시지들: 응답 없이 버린다(저장하지 않음)
        assertThat(send(session, sent.get(2), false)).isEqualTo(ChatReply.NONE);
        assertThat(send(session, sent.get(3), false)).isEqualTo(ChatReply.NONE);
        assertThat(chat.messageCount(room)).isEqualTo(1);

        // 브라우저: 확정받지 못한 것부터 처음 순서대로, 첫 메시지에만 resume
        assertThat(send(session, sent.get(1), true)).isInstanceOf(ChatReply.Ack.class);
        assertThat(session.isHalted()).isFalse();
        assertThat(send(session, sent.get(2), false)).isInstanceOf(ChatReply.Ack.class);
        assertThat(send(session, sent.get(3), false)).isInstanceOf(ChatReply.Ack.class);

        assertThat(savedOrder()).containsExactlyElementsOf(sent);
        chat.assertContiguous(room);
    }

    @Test
    @DisplayName("[4.3] 끊긴 세션(closed)은 resume이 와도 처리하지 않는다")
    void closedSessionDropsEverything() {
        ChatSendSession session = new ChatSendSession();
        session.close();
        assertThat(send(session, UUID.randomUUID().toString(), true)).isEqualTo(ChatReply.NONE);
        assertThat(chat.messageCount(room)).isZero();
    }

    @Test
    @DisplayName("[4.6] 분류되지 않은 예외가 일시적이면: 첫 실패에 멈춤 → 재개 후 순서대로 성공")
    void unclassifiedTransientRecovers() {
        UUID flaky = UUID.randomUUID();
        AtomicInteger failures = new AtomicInteger();
        doAnswer(inv -> {
            if (inv.getArgument(2).equals(flaky) && failures.incrementAndGet() == 1) {
                throw new IllegalStateException("주입: 한 번만 나는 알 수 없는 오류");
            }
            return inv.callRealMethod();
        }).when(writer).writeText(anyLong(), anyLong(), any(), anyString());

        ChatSendSession session = new ChatSendSession();
        String a = flaky.toString();
        String b = UUID.randomUUID().toString();

        assertThat(send(session, a, false)).isEqualTo(new ChatReply.Error(room, a, ChatSendError.SERVER_RETRY_EXHAUSTED));
        assertThat(failures.get()).as("분류되지 않은 예외는 재시도하지 않는다").isEqualTo(1);
        assertThat(session.isHalted()).isTrue();
        assertThat(send(session, b, false)).isEqualTo(ChatReply.NONE);

        assertThat(send(session, a, true)).isInstanceOf(ChatReply.Ack.class);
        assertThat(send(session, b, false)).isInstanceOf(ChatReply.Ack.class);
        assertThat(savedOrder()).containsExactly(a, b);
    }

    @Test
    @DisplayName("[4.6] 분류되지 않은 예외가 결정적이면: 첫 실패에 멈춤 → 재개 후 같은 메시지가 또 실패하면 그 메시지만 INTERNAL_ERROR, 멈추지 않고 뒤 메시지는 계속 저장")
    void unclassifiedDeterministicRejectsOnlyThatMessage() {
        UUID broken = UUID.randomUUID();
        doAnswer(inv -> {
            if (inv.getArgument(2).equals(broken)) {
                throw new IllegalStateException("주입: 이 메시지는 항상 실패");
            }
            return inv.callRealMethod();
        }).when(writer).writeText(anyLong(), anyLong(), any(), anyString());

        ChatSendSession session = new ChatSendSession();
        String before = UUID.randomUUID().toString();
        String bad = broken.toString();
        String after1 = UUID.randomUUID().toString();
        String after2 = UUID.randomUUID().toString();

        assertThat(send(session, before, false)).isInstanceOf(ChatReply.Ack.class);
        assertThat(send(session, bad, false)).isEqualTo(new ChatReply.Error(room, bad, ChatSendError.SERVER_RETRY_EXHAUSTED));
        assertThat(send(session, after1, false)).isEqualTo(ChatReply.NONE);

        assertThat(send(session, bad, true)).isEqualTo(new ChatReply.Error(room, bad, ChatSendError.INTERNAL_ERROR));
        assertThat(session.isHalted()).as("영구 실패로 보고 멈추지 않는다").isFalse();
        assertThat(send(session, after1, false)).isInstanceOf(ChatReply.Ack.class);
        assertThat(send(session, after2, false)).isInstanceOf(ChatReply.Ack.class);

        assertThat(savedOrder()).containsExactly(before, after1, after2);
        chat.assertContiguous(room);
    }

    // ===== 도우미 =====

    private ChatReply send(ChatSendSession session, String clientMessageId, boolean resume) {
        return handler.handle(session, me.id(), room,
                new ChatSendRequest(clientMessageId, "본문 " + clientMessageId, resume ? Boolean.TRUE : null));
    }

    /** 저장된 TEXT의 clientMessageId를 번호 순으로. */
    private List<String> savedOrder() {
        return jdbc.queryForList(
                "SELECT client_message_id::text FROM message WHERE room_id = ? AND type = 'TEXT' ORDER BY seq",
                String.class, room);
    }
}
