package SeSAC.Dance_Assessment.Service.Chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 메시지 저장의 재시도 래퍼. <b>트랜잭션이 없다</b> — 매 시도가 {@link MessageWriter}의 새 트랜잭션이다.
 *
 * <table>
 *   <tr><th>실패</th><th>처리</th></tr>
 *   <tr><td>다시 해 볼 만한 실패 ({@link ChatFailures})</td><td>최대 {@code maxAttempts}회,
 *       대기 100 → 200 → 400ms. 기다리는 동안 이 세션의 다음 메시지는 처리되지 않는다</td></tr>
 *   <tr><td>멤버 아님, 입력 오류</td><td>{@link SendOutcome.Rejected} — 그 메시지만 거절</td></tr>
 *   <tr><td>같은 메시지가 이미 있음</td><td>{@link SendOutcome.Saved}(duplicate) — 기존 번호로 성공.
 *       멱등성은 "오류를 안 내는 것"이 아니라 "같은 결과를 돌려주는 것"이다</td></tr>
 *   <tr><td>그 밖의 예외</td><td>{@link SendOutcome.Unclassified} — 재시도하지 않는다</td></tr>
 * </table>
 *
 * <p>호출한 스레드에서 끝까지 처리한다(다른 스레드로 넘기지 않는다). 세션 차례 처리는 처리
 * 메서드가 반환되는 순간 다음 메시지를 내보내므로, 넘기면 같은 사람의 순서가 섞인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    /** 코드 포인트 기준. 브라우저({@code [...s].length})·DB({@code char_length})와 같은 기준이다. */
    public static final int MAX_CONTENT_LENGTH = 2000;

    // UUID.fromString은 "1-1-1-1-1"도 받아준다. 브라우저가 만드는 정식 표기만 받는다.
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final MessageWriter writer;
    private final ChatSendProperties properties;
    private final ChatSendMetrics metrics;

    /** 사람이 보낸 메시지. */
    public SendOutcome sendText(long senderId, long roomId, String rawClientMessageId, String content) {
        UUID clientMessageId = parseClientMessageId(rawClientMessageId);
        if (clientMessageId == null || !isValidContent(content)) {
            log.info("[채팅 입력 오류] roomId={} senderId={} clientMessageId={} length={}",
                    roomId, senderId, rawClientMessageId, content == null ? null : content.codePointCount(0, content.length()));
            metrics.invalid();
            return new SendOutcome.Rejected(ChatSendError.INVALID_MESSAGE);
        }
        return withRetry(roomId, clientMessageId,
                () -> writer.writeText(roomId, senderId, clientMessageId, content));
    }

    /** 입장·나가기·추방 안내. {@code eventId}는 사건마다 한 번 만든 UUID다. */
    public SendOutcome sendSystem(long roomId, UUID eventId, String content) {
        return withRetry(roomId, eventId, () -> writer.writeSystem(roomId, eventId, content));
    }

    private SendOutcome withRetry(long roomId, UUID clientMessageId, Supplier<SavedMessage> attempt) {
        long startedAt = System.nanoTime();
        Throwable lastFailure = null;
        int attempts = 0;

        while (true) {
            attempts++;
            try {
                SavedMessage saved = attempt.get();
                if (attempts > 1) {
                    log.info("[채팅 저장 재시도 성공] roomId={} clientMessageId={} attempts={}",
                            roomId, clientMessageId, attempts);
                }
                return new SendOutcome.Saved(saved, false);
            } catch (DuplicateMessageException e) {
                log.info("[채팅 중복 메시지] roomId={} clientMessageId={} 기존 seq={}",
                        roomId, clientMessageId, e.existing().seq());
                metrics.duplicate();
                return new SendOutcome.Saved(e.existing(), true);
            } catch (NotChatMemberException e) {
                return new SendOutcome.Rejected(ChatSendError.NOT_MEMBER);
            } catch (RuntimeException e) {
                if (!ChatFailures.isRetryable(e)) {
                    metrics.unclassified();
                    return new SendOutcome.Unclassified(e);
                }
                lastFailure = e;
            }

            if (attempts >= properties.maxAttempts()) {
                break;
            }
            Duration backoff = properties.backoffBefore(attempts);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            if (elapsed.compareTo(properties.retryCutoff()) >= 0) {
                log.warn("[채팅 저장 재시도 중단] 재시도 마감 {}ms 경과(경과 {}ms) roomId={} clientMessageId={}",
                        properties.retryCutoff().toMillis(), elapsed.toMillis(), roomId, clientMessageId);
                break;
            }
            log.warn("[채팅 저장 재시도] roomId={} clientMessageId={} attempt={} 대기={}ms 원인={}",
                    roomId, clientMessageId, attempts, backoff.toMillis(), describe(lastFailure));
            metrics.retried(lastFailure);
            if (!sleep(backoff)) {
                break;
            }
        }

        metrics.exhausted();
        log.error("[채팅 저장 재시도 소진] roomId={} clientMessageId={} attempts={} 원인={}",
                roomId, clientMessageId, attempts, describe(lastFailure));
        return new SendOutcome.RetryExhausted(lastFailure, attempts);
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static UUID parseClientMessageId(String raw) {
        if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
            return null;
        }
        return UUID.fromString(raw);
    }

    static boolean isValidContent(String content) {
        return content != null
                && !content.isBlank()
                && content.codePointCount(0, content.length()) <= MAX_CONTENT_LENGTH;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "-";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return t.getClass().getSimpleName() + " / " + root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
