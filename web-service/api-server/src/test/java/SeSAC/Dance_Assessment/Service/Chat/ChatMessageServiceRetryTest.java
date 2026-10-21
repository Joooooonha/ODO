package SeSAC.Dance_Assessment.Service.Chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 재시도 분류와 횟수·시간 상한(4.2, 4.5). DB 없이 저장을 흉내 낸다. */
class ChatMessageServiceRetryTest {

    private final MessageWriter writer = mock(MessageWriter.class);
    private final ChatSendMetrics metrics = new ChatSendMetrics();

    private ChatMessageService service(Duration cutoff, Duration... backoff) {
        return new ChatMessageService(writer, new ChatSendProperties(4, List.of(backoff), cutoff), metrics, new PublishDeferral(false));
    }

    private static SQLException state(String sqlState) {
        return new SQLException("주입", sqlState);
    }

    @Test
    @DisplayName("재시도 대상: 40001·40P01·57014·08xxx, 커넥션을 못 얻음, 풀 대기 시간 초과")
    void retryableClassification() {
        assertThat(ChatFailures.isRetryable(new CannotAcquireLockException("x", state("40P01")))).isTrue();
        assertThat(ChatFailures.isRetryable(new RuntimeException(state("40001")))).isTrue();
        assertThat(ChatFailures.isRetryable(new RuntimeException(state("57014")))).isTrue();
        assertThat(ChatFailures.isRetryable(new RuntimeException(state("08006")))).isTrue();
        assertThat(ChatFailures.isRetryable(new CannotGetJdbcConnectionException("x"))).isTrue();
        assertThat(ChatFailures.isRetryable(new CannotCreateTransactionException("x"))).isTrue();
        assertThat(ChatFailures.isRetryable(new RuntimeException(
                new SQLTransientConnectionException("Connection is not available, request timed out")))).isTrue();
    }

    @Test
    @DisplayName("재시도 대상이 아님: 제약 위반(23505 등), 버그성 예외 → 분류되지 않은 예외")
    void notRetryable() {
        assertThat(ChatFailures.isRetryable(new DataIntegrityViolationException("x", state("23505")))).isFalse();
        assertThat(ChatFailures.isRetryable(new IllegalStateException("bug"))).isFalse();
        assertThat(ChatFailures.isRetryable(new NullPointerException())).isFalse();
    }

    @Test
    @DisplayName("최대 4번 시도(최초 + 재시도 3) 뒤 소진. 대기는 100 → 200 → 400ms")
    void maxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        when(writer.writeText(anyLong(), anyLong(), any(), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new RuntimeException(state("57014"));
        });

        long started = System.nanoTime();
        SendOutcome outcome = service(Duration.ofSeconds(3), Duration.ofMillis(100), Duration.ofMillis(200),
                Duration.ofMillis(400)).sendText(1, 1, UUID.randomUUID().toString(), "x");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(outcome).isInstanceOf(SendOutcome.RetryExhausted.class);
        assertThat(((SendOutcome.RetryExhausted) outcome).attempts()).isEqualTo(4);
        assertThat(calls.get()).isEqualTo(4);
        assertThat(elapsedMs).as("대기 합 700ms").isBetween(650L, 2000L);
        assertThat(metrics.retries("57014")).isEqualTo(3);
        assertThat(metrics.exhaustedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("[4.5] 첫 시도를 시작한 뒤 재시도 마감 시간이 지났으면 횟수가 남아도 새 시도를 시작하지 않는다")
    void cutoffStopsSlowFailures() {
        AtomicInteger calls = new AtomicInteger();
        when(writer.writeText(anyLong(), anyLong(), any(), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            Thread.sleep(300);                       // 느리게 실패하는 시도(시간 초과 같은)
            throw new RuntimeException(state("57014"));
        });

        SendOutcome outcome = service(Duration.ofMillis(250), Duration.ofMillis(10))
                .sendText(1, 1, UUID.randomUUID().toString(), "x");

        assertThat(outcome).isInstanceOf(SendOutcome.RetryExhausted.class);
        assertThat(calls.get()).as("첫 시도가 마감을 넘겨 재시도하지 않음").isEqualTo(1);
    }

    @Test
    @DisplayName("분류되지 않은 예외는 재시도하지 않는다")
    void unclassifiedIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        when(writer.writeText(anyLong(), anyLong(), any(), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new IllegalStateException("bug");
        });

        SendOutcome outcome = service(Duration.ofSeconds(3), Duration.ofMillis(10))
                .sendText(1, 1, UUID.randomUUID().toString(), "x");

        assertThat(outcome).isInstanceOf(SendOutcome.Unclassified.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("멤버 아님은 재시도하지 않고 NOT_MEMBER로 거절")
    void notMemberIsRejectedOnce() {
        AtomicInteger calls = new AtomicInteger();
        when(writer.writeText(anyLong(), anyLong(), any(), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new NotChatMemberException(1, 1);
        });

        assertThat(service(Duration.ofSeconds(3), Duration.ofMillis(10)).sendText(1, 1, UUID.randomUUID().toString(), "x"))
                .isEqualTo(new SendOutcome.Rejected(ChatSendError.NOT_MEMBER));
        assertThat(calls.get()).isEqualTo(1);
    }
}
