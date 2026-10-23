package SeSAC.Dance_Assessment.Service.Chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 메시지 저장 뒤 Redis 발행을 <b>커넥션을 돌려준 다음</b>으로 미룬다(기본 끔).
 *
 * <p>지금은 커밋 뒤 발행({@code @TransactionalEventListener})이 트랜잭션 정리 전에 돌아서, 발행하는 동안(0.2~0.5ms)
 * DB 커넥션을 쥐고 있다. 메시지 처리 스레드를 8개로 늘리자 Hikari 대기가 생겨서, 그 몫만큼 커넥션을 빨리 돌려주려는 것이다.
 *
 * <ul>
 *   <li><b>같은 스레드에서 동기로</b> 실행한다(다른 스레드로 넘기지 않는다). 세션 차례 처리가 처리 메서드 반환 순간
 *       다음 메시지를 내보내므로, 반환 전에 발행까지 끝난다 — 지금과 같다</li>
 *   <li>같은 방의 발행 순서도 지금과 같다. 커밋한 그 스레드가 커밋 직후 발행한다. 사이에 끼는 것은 커넥션 반납뿐이다.
 *       스레드끼리의 커밋 순서와 발행 순서가 어긋날 수 있는 것은 지금도 같다(브라우저가 번호로 맞춘다)</li>
 *   <li>{@link #around} 안(사람이 보낸 메시지 한 건의 저장 시도들)에서만 미룬다. 그 밖(안내 메시지 등 바깥 트랜잭션에
 *       묶일 수 있는 경로)은 지금처럼 바로 발행한다 — 미룬 일이 다음 메시지로 새지 않게</li>
 * </ul>
 */
@Component
public class PublishDeferral {

    private final boolean enabled;
    private final ThreadLocal<List<Runnable>> pending = new ThreadLocal<>();

    public PublishDeferral(@Value("${chat.send.publish-after-release:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** {@code work}가 끝난 뒤(트랜잭션이 끝나 커넥션을 돌려준 뒤) 그 안에서 맡은 발행을 이 스레드에서 실행한다. */
    public <T> T around(Supplier<T> work) {
        if (!enabled || pending.get() != null) {
            return work.get();
        }
        List<Runnable> deferred = new ArrayList<>(1);
        pending.set(deferred);
        try {
            return work.get();
        } finally {
            pending.remove();
            for (Runnable publish : deferred) {
                publish.run();
            }
        }
    }

    /** {@link #around} 안이면 맡고 true. 아니면 false — 호출한 쪽이 바로 발행한다. */
    public boolean defer(Runnable publish) {
        List<Runnable> deferred = pending.get();
        if (deferred == null) {
            return false;
        }
        deferred.add(publish);
        return true;
    }
}
