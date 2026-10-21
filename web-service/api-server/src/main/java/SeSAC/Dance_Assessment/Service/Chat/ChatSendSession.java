package SeSAC.Dance_Assessment.Service.Chat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 웹소켓 세션 하나의 전송 상태. 세션 안의 메시지는 한 번에 하나씩 처리되지만 처리 스레드가
 * 바뀌므로 가시성을 보장하는 타입을 쓴다.
 *
 * <ul>
 *   <li>{@code halted} — 재시도 소진(또는 분류되지 않은 예외의 첫 발생)으로 멈춤.
 *       {@code resume: true}가 붙은 메시지가 오면 풀린다</li>
 *   <li>{@code closed} — 연결이 끊김. <b>다시 풀 수 없다.</b> {@code halted}와 따로 두는 이유는
 *       대기열에 남은 {@code resume: true} 메시지가 끊긴 세션의 멈춤을 풀어 버리기 때문이다</li>
 *   <li>분류되지 않은 예외로 실패한 UUID — 재개 뒤 같은 메시지가 또 실패하면 영구 실패로 본다(4.6)</li>
 * </ul>
 */
public class ChatSendSession {

    /** 기억할 UUID 수. 작게 유지한다 — 넘치면 오래된 것부터 잊는다(잊으면 한 번 더 멈출 뿐이다). */
    static final int MAX_REMEMBERED_FAILURES = 64;

    private final AtomicBoolean halted = new AtomicBoolean();
    private volatile boolean closed;
    private final Set<UUID> unclassifiedFailures = new LinkedHashSet<>();

    public boolean isHalted() {
        return halted.get();
    }

    void halt() {
        halted.set(true);
    }

    void resume() {
        halted.set(false);
    }

    public boolean isClosed() {
        return closed;
    }

    /** 연결 종료. 되돌릴 수 없다. */
    public void close() {
        closed = true;
    }

    /** @return 이 UUID가 분류되지 않은 예외로 실패한 것이 처음이면 true */
    synchronized boolean recordUnclassifiedFailure(UUID clientMessageId) {
        if (unclassifiedFailures.contains(clientMessageId)) {
            return false;
        }
        if (unclassifiedFailures.size() >= MAX_REMEMBERED_FAILURES) {
            UUID oldest = unclassifiedFailures.iterator().next();
            unclassifiedFailures.remove(oldest);
        }
        unclassifiedFailures.add(clientMessageId);
        return true;
    }

    synchronized void forgetFailure(UUID clientMessageId) {
        unclassifiedFailures.remove(clientMessageId);
    }
}
