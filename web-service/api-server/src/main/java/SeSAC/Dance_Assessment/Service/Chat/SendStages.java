package SeSAC.Dance_Assessment.Service.Chat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 메시지 1건 처리 시간 분해(10/20 작업 3, 측정용 — 기능 동작은 바꾸지 않는다).
 *
 * <p>R1에서 인바운드 실행기 4스레드 × 1건 {@code chat.send.duration} 평균 3.9ms가 처리량 상한(서버당 ≈ 1,025건/초)을
 * 정했다. 그 3.9ms가 어디에 쓰이는지 실제 코드 단계마다 잰다. 한 스레드가 한 메시지를 처음부터 끝까지 처리하므로
 * (ChatMessageService: 다른 스레드로 넘기지 않는다) 스레드 하나의 시계로 "앞 표시부터 이 표시까지"를 단계 시간으로 기록한다.
 *
 * <pre>
 * 단계(stage)        앞 표시 → 이 표시
 * begin              컨트롤러 진입 → 트랜잭션 안 첫 줄 (본문 읽기·세션 검사·트랜잭션 시작 = 커넥션 획득·autocommit 끄기)
 * seq                ① UPDATE chat_room … RETURNING (방 행 잠금 대기 포함)
 * member             ② 보낸 사람 ACTIVE 확인
 * insert             ③ INSERT message
 * body_end           메서드 반환 → 커밋 직전(이벤트 등록·프록시)
 * commit             커밋(Hibernate flush + COMMIT 왕복, WAL 기록)
 * after_commit       커밋 뒤 → 발행 리스너 시작
 * publish            Redis 발행(JSON + PUBLISH 왕복). 커넥션을 아직 쥐고 있다(정리는 커밋 뒤 동기화가 끝난 다음)
 * release            발행 끝 → 재시도 래퍼로 돌아옴(커넥션 정리·풀 반납)
 * ack                확인 응답 보내기(바깥 채널에 넘김)
 * </pre>
 *
 * 타이머는 백분위수 없이 합·횟수·최대만 둔다(메시지마다 열 번 기록하므로 싸게). 시스템 메시지처럼 컨트롤러를 거치지 않는
 * 저장은 시계가 없어 기록하지 않는다. 재시도가 있으면 다음 시도의 begin에 대기 시간이 들어간다(측정에서 재시도 0을 확인).
 */
@Component
public class SendStages {

    public static final String BEGIN = "begin";
    public static final String SEQ = "seq";
    public static final String MEMBER = "member";
    public static final String INSERT = "insert";
    public static final String BODY_END = "body_end";
    public static final String COMMIT = "commit";
    public static final String AFTER_COMMIT = "after_commit";
    public static final String PUBLISH = "publish";
    public static final String RELEASE = "release";
    public static final String ACK = "ack";

    private static final ThreadLocal<long[]> LAST_MARK = new ThreadLocal<>();

    private final MeterRegistry registry;
    private final boolean enabled;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public SendStages(MeterRegistry registry, @Value("${chat.metrics.send-stages:true}") boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
    }

    /** 이 스레드에서 메시지 한 건 처리를 시작한다. */
    public void start() {
        if (enabled) {
            LAST_MARK.set(new long[]{System.nanoTime()});
        }
    }

    /** 앞 표시부터 지금까지를 {@code stage}로 기록한다. 시계가 없으면(시작하지 않은 스레드) 아무것도 하지 않는다. */
    public void mark(String stage) {
        long[] last = LAST_MARK.get();
        if (last == null) {
            return;
        }
        long now = System.nanoTime();
        timers.computeIfAbsent(stage, s -> Timer.builder("chat.send.stage").tag("stage", s).register(registry))
                .record(now - last[0], TimeUnit.NANOSECONDS);
        last[0] = now;
    }

    public void finish() {
        LAST_MARK.remove();
    }

    /** 지금 트랜잭션의 커밋 앞뒤에 표시를 건다(커밋 뒤 리스너보다 먼저 불리도록 가장 높은 우선순위). */
    public void watchCommit() {
        if (LAST_MARK.get() == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
            }

            @Override
            public void beforeCommit(boolean readOnly) {
                mark(BODY_END);
            }

            @Override
            public void afterCommit() {
                mark(COMMIT);
            }
        });
    }
}
