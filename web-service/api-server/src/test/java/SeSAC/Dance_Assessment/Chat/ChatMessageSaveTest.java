package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.Service.Chat.ChatMessageService;
import SeSAC.Dance_Assessment.Service.Chat.ChatSendError;
import SeSAC.Dance_Assessment.Service.Chat.SavedMessage;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 메시지 저장 경로(4.1·4.2, E6). 순차 실행. 동시 실행은 {@link ChatConcurrencyTest}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChatMessageSaveTest {

    // 이모지는 UTF-16으로 2칸이지만 코드 포인트로는 1자다. 세 층이 같은 기준으로 세는지 본다.
    private static final String EMOJI = "😀";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    ChatMessageService chatMessageService;

    @Autowired
    JdbcTemplate jdbc;

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
        room = chat.teamRoomId(me, teams.createTeam(me, "저장 팀"));
    }

    @Test
    @DisplayName("방별 seq가 1부터 빈틈 없이 늘고 chat_room.last_seq와 일치한다. 시각도 번호 순서를 따른다")
    void seqIsContiguous() {
        List<SavedMessage> saved = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            saved.add(chat.send(me, room, "m" + i));
        }
        assertThat(saved).extracting(SavedMessage::seq).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        chat.assertContiguous(room);
        for (int i = 1; i < saved.size(); i++) {
            assertThat(saved.get(i).createdAt()).isAfterOrEqualTo(saved.get(i - 1).createdAt());
        }
        assertThat(saved.get(9).createdAt()).isEqualTo(jdbc.queryForObject(
                "SELECT created_at FROM message WHERE room_id = ? AND seq = 10", java.time.OffsetDateTime.class, room)
                .toInstant());
    }

    @Test
    @DisplayName("[멱등] 같은 clientMessageId 재전송 → 새 행 없이 기존 seq로 성공(duplicate). 번호도 소비하지 않는다")
    void duplicateReturnsExistingSeq() {
        UUID id = UUID.randomUUID();
        SendOutcome first = chat.trySend(me, room, id, "한 번만");
        chat.send(me, room, "다른 메시지");
        SendOutcome again = chat.trySend(me, room, id, "한 번만");

        assertThat(first).isInstanceOf(SendOutcome.Saved.class);
        SendOutcome.Saved firstSaved = (SendOutcome.Saved) first;
        SendOutcome.Saved againSaved = (SendOutcome.Saved) again;
        assertThat(firstSaved.duplicate()).isFalse();
        assertThat(againSaved.duplicate()).isTrue();
        assertThat(againSaved.message().seq()).isEqualTo(firstSaved.message().seq());
        assertThat(againSaved.message().messageId()).isEqualTo(firstSaved.message().messageId());
        assertThat(chat.messageCount(room)).isEqualTo(2);
        assertThat(chat.lastSeq(room)).isEqualTo(2);
    }

    @Test
    @DisplayName("남의 clientMessageId를 써도 남의 번호를 돌려받지 않는다 — 보낸 사람이 키에 들어 있다")
    void foreignUuidDoesNotLeak() throws Exception {
        LoggedIn other = users.create("남");
        teams.rejoin(other, teams.joinCode(me, jdbc.queryForObject(
                "SELECT team_id FROM chat_room WHERE room_id = ?", Long.class, room)));
        UUID id = UUID.randomUUID();
        SavedMessage mine = ((SendOutcome.Saved) chat.trySend(me, room, id, "내 것")).message();
        SendOutcome theirs = chat.trySend(other, room, id, "같은 UUID");

        assertThat(theirs).isInstanceOf(SendOutcome.Saved.class);
        assertThat(((SendOutcome.Saved) theirs).duplicate()).isFalse();
        assertThat(((SendOutcome.Saved) theirs).message().seq()).isNotEqualTo(mine.seq());
    }

    @Test
    @DisplayName("[E6] 빈 내용·공백·null·2,001자·형식이 틀린 UUID → INVALID_MESSAGE, 행도 번호도 없다")
    void invalidInput() {
        assertInvalid(UUID.randomUUID().toString(), "");
        assertInvalid(UUID.randomUUID().toString(), "   \n ");
        assertInvalid(UUID.randomUUID().toString(), null);
        assertInvalid(UUID.randomUUID().toString(), EMOJI.repeat(2001));
        assertInvalid("not-a-uuid", "내용");
        assertInvalid("1-1-1-1-1", "UUID.fromString은 받아주지만 정식 표기가 아니다");
        assertInvalid(null, "내용");
        assertThat(chat.lastSeq(room)).isZero();
        assertThat(chat.messageCount(room)).isZero();
    }

    @Test
    @DisplayName("[E6] 이모지 2,000자는 통과하고 DB의 char_length도 2,000이다 (브라우저·서버·DB가 같은 기준)")
    void emojiLengthAgreesAcrossLayers() {
        String content = EMOJI.repeat(2000);
        assertThat(content.length()).as("UTF-16으로는 4,000칸").isEqualTo(4000);
        SavedMessage saved = chat.send(me, room, content);
        assertThat(jdbc.queryForObject("SELECT char_length(content) FROM message WHERE message_id = ?",
                Integer.class, saved.messageId())).isEqualTo(2000);
    }

    @Test
    @DisplayName("[안전망] DB CHECK도 2,000자를 넘는 TEXT를 거부한다 (서버 검증을 우회한 경우)")
    void dbCheckRejectsTooLong() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO message (room_id, seq, sender_id, type, content, client_message_id, created_at)"
                                + " VALUES (?, 1, ?, 'TEXT', ?, ?, now())",
                        room, me.id(), "가".repeat(2001), UUID.randomUUID()))
                .hasMessageContaining("message_content_check");
    }

    private void assertInvalid(String clientMessageId, String content) {
        SendOutcome outcome = chatMessageService.sendText(me.id(), room, clientMessageId, content);
        assertThat(outcome).as("clientMessageId=%s", clientMessageId)
                .isEqualTo(new SendOutcome.Rejected(ChatSendError.INVALID_MESSAGE));
    }
}
