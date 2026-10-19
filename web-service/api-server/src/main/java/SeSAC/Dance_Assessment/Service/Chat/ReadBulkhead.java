package SeSAC.Dance_Assessment.Service.Chat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 읽음 벌크헤드(Task-chat-read-improve 3절, 개선 A): 서버 한 대에서 동시에 처리하는 읽음 보고 수의 상한 K.
 * 넘치면 <b>기다리지 않고</b> 바로 거절한다(호출자가 429로 응답).
 *
 * <p>왜 읽음 쪽을 막나: 과부하 때 메시지 SEND(인바운드 실행기 스레드 4개)와 읽음 보고(Tomcat 스레드 최대 200)가
 * CPU를 나눠 가져 메시지가 10초 밀렸다(2-4 시나리오 2). 읽음 보고는 더 큰 값만 의미가 있어 버려도 다음 보고(또는
 * 화면의 재전송)가 덮는다. 버려도 되는 쪽에 상한을 걸어 버리면 안 되는 쪽(메시지)을 지킨다. 기다리게 하면 Tomcat
 * 스레드를 붙잡은 채 CPU 차례를 다투게 되어 막으려던 문제가 그대로 남는다.
 *
 * <p>{@code chat.read.bulkhead.max-concurrent} = 0(기본)이면 끈다: 항상 통과하고 동작은 기준선과 같다.
 * 동시 처리 수 게이지({@code chat.read.inflight})는 켜고 끔과 상관없이 센다(K를 정하는 근거).
 */
@Component
public class ReadBulkhead {

    private final int maxConcurrent;
    private final Semaphore permits;
    private final AtomicInteger inflight = new AtomicInteger();

    public ReadBulkhead(@Value("${chat.read.bulkhead.max-concurrent:0}") int maxConcurrent, MeterRegistry registry) {
        this.maxConcurrent = Math.max(0, maxConcurrent);
        this.permits = this.maxConcurrent > 0 ? new Semaphore(this.maxConcurrent) : null;
        Gauge.builder("chat.read.inflight", inflight, AtomicInteger::get).register(registry);
        Gauge.builder("chat.read.bulkhead.limit", () -> this.maxConcurrent).register(registry);
    }

    /** @return 처리해도 되면 true(그때는 반드시 {@link #release()}). 꺼져 있으면 항상 true */
    public boolean tryAcquire() {
        if (permits != null && !permits.tryAcquire()) {
            return false;
        }
        inflight.incrementAndGet();
        return true;
    }

    public void release() {
        inflight.decrementAndGet();
        if (permits != null) {
            permits.release();
        }
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }
}
