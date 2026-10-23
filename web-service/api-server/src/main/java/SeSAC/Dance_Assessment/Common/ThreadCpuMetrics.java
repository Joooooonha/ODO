package SeSAC.Dance_Assessment.Common;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스레드 풀별 CPU 시간 합 {@code chat.threads.cpu{pool}}(초, 누적) — 측정에서만 켠다({@code chat.metrics.thread-cpu=true}).
 *
 * <p>과부하 때 "읽음(Tomcat 요청 스레드)이 메시지(인바운드 실행기)보다 CPU를 더 가져간다"를 추정이 아니라 숫자로
 * 보려고 만든다. 스레드 이름 접두어로 나눈다.
 * <ul>
 *   <li>{@code inbound} — {@code clientInboundChannel-}: STOMP SEND(메시지 저장·확정)</li>
 *   <li>{@code tomcat} — {@code http-nio-…-exec-}: HTTP 요청(읽음 보고·조회) + 웹소켓 프레임 읽기</li>
 *   <li>{@code outbound} — {@code clientOutboundChannel-}: 세션으로 프레임 쓰기(압축 포함)</li>
 *   <li>{@code redis} — {@code redis-dispatch-}: Redis 수신 → 브로커(READ 구간 계산 포함)</li>
 *   <li>{@code other} — 나머지 Java 스레드(JIT 컴파일러, Lettuce, 스케줄러 등). GC·JVM 내부 스레드는
 *       ThreadMXBean에 없다 → 프로세스 CPU({@code process.cpu.usage})와의 차이로 본다</li>
 * </ul>
 *
 * <p>수집할 때(Prometheus 5초)마다 모든 스레드의 CPU 시간을 읽어 지난번과의 차이를 풀별로 더한다. 그 사이에 끝난
 * 스레드의 마지막 몫은 빠진다(풀 스레드는 오래 살아서 작다). 누적이라 단조 증가한다.
 */
@Component
@ConditionalOnProperty(name = "chat.metrics.thread-cpu", havingValue = "true")
public class ThreadCpuMetrics implements MeterBinder {

    static final List<String> POOLS = List.of("inbound", "tomcat", "outbound", "redis", "other");
    private static final long MIN_REFRESH_NANOS = 500_000_000L;

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final Map<Long, Long> lastCpu = new HashMap<>();
    private final Map<String, Long> totals = new LinkedHashMap<>();
    private long lastRefresh;

    public ThreadCpuMetrics() {
        POOLS.forEach(p -> totals.put(p, 0L));
        if (threads.isThreadCpuTimeSupported() && !threads.isThreadCpuTimeEnabled()) {
            threads.setThreadCpuTimeEnabled(true);
        }
    }

    static String poolOf(String name) {
        if (name == null) {
            return "other";
        }
        if (name.startsWith("clientInboundChannel-")) {
            return "inbound";
        }
        if (name.startsWith("clientOutboundChannel-")) {
            return "outbound";
        }
        if (name.startsWith("http-nio-") && name.contains("-exec-")) {
            return "tomcat";
        }
        if (name.startsWith("redis-dispatch-")) {
            return "redis";
        }
        return "other";
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String pool : POOLS) {
            FunctionCounter.builder("chat.threads.cpu", this, m -> m.seconds(pool))
                    .tag("pool", pool)
                    .baseUnit("seconds")
                    .register(registry);
        }
    }

    synchronized double seconds(String pool) {
        long now = System.nanoTime();
        if (now - lastRefresh >= MIN_REFRESH_NANOS) {
            refresh();
            lastRefresh = now;
        }
        return totals.get(pool) / 1e9;
    }

    private void refresh() {
        long[] ids = threads.getAllThreadIds();
        ThreadInfo[] infos = threads.getThreadInfo(ids);
        Map<Long, Long> seen = new HashMap<>(ids.length * 2);
        for (int i = 0; i < ids.length; i++) {
            long cpu = threads.getThreadCpuTime(ids[i]);
            if (cpu < 0 || infos[i] == null) {
                continue;
            }
            long delta = cpu - lastCpu.getOrDefault(ids[i], 0L);
            if (delta > 0) {
                totals.merge(poolOf(infos[i].getThreadName()), delta, Long::sum);
            }
            seen.put(ids[i], cpu);
        }
        lastCpu.clear();
        lastCpu.putAll(seen);
    }
}
