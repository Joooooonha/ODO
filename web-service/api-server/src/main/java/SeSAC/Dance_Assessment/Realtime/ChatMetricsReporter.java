package SeSAC.Dance_Assessment.Realtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * 채팅·커넥션 풀·실행기 지표를 주기적으로 한 줄씩 로그에 남긴다(4.5 측정 준비).
 * 운영에서는 {@code journalctl -u dance-api | grep "채팅 지표"}로 본다.
 *
 * <p>값은 서버가 뜬 뒤 누적(횟수)이거나 지금 순간(게이지)이다. 저장 처리 시간과 대기열 대기 시간은
 * 평균/p50/p95/p99/최대(ms) — 백분위수·최대는 최근 한 간격 안팎이다({@code LatencyTimers}).
 * 간격은 {@code chat.metrics.log-interval}(운영 60초, 측정 때 10초 등으로 바꾼다).
 *
 * <ul>
 *   <li>커넥션 풀(HikariCP, Boot 자동 연결): 획득 시간, 대기 스레드 수, 활성 커넥션 수</li>
 *   <li>인바운드 실행기({@code clientInboundChannelExecutor}, Boot 자동 연결): 활성 스레드, 대기 작업</li>
 *   <li>저장: 처리 시간(결과별), 원인별 재시도, 소진, 분류되지 않은 예외</li>
 *   <li>세션: 대기열 대기 시간, 서버가 끊은 세션(이유별), 활성 세션, Redis 방 구독 수, 발행 실패</li>
 *   <li>읽음: 보고 수(결과별), 처리 시간(결과별), 스냅샷 조회 시간·평균 행 수</li>
 *   <li>Redis 받는 스레드(메시지 전달·읽음 계산·제어가 한 스레드): 기다린 시간, 처리 시간(종류별), 지금 기다리는 수</li>
 *   <li>종류별 발행 수·나간 프레임 수</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMetricsReporter {

    private final MeterRegistry registry;

    @Scheduled(initialDelayString = "${chat.metrics.log-interval:60s}", fixedDelayString = "${chat.metrics.log-interval:60s}")
    public void report() {
        log.info("[채팅 지표] {}", summary());
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("세션=").append(gauge("chat.sessions.active"))
                .append(" 방구독=").append(gauge("chat.redis.room.subscriptions"));
        sb.append(" | 저장 ").append(timers("chat.send.duration", "outcome"));
        sb.append(" 재시도=").append(countersByTag("chat.send.retries", "cause"))
                .append(" 소진=").append(counter("chat.send.exhausted"))
                .append(" 미분류=").append(counter("chat.send.unclassified"))
                .append(" 중복=").append(counter("chat.send.duplicates"));
        sb.append(" | 대기열대기 ").append(timer(registry.find("chat.session.queue.wait").timer()))
                .append(" 끊김=").append(countersByTag("chat.session.disconnects", "reason"))
                .append(" 발행실패=").append(counter("chat.redis.publish.failures"));
        sb.append(" | 풀 활성=").append(gauge("hikaricp.connections.active"))
                .append(" 대기=").append(gauge("hikaricp.connections.pending"))
                .append(" 획득 ").append(timer(registry.find("hikaricp.connections.acquire").timer()));
        sb.append(" | 인바운드 활성=").append(gaugeTagged("executor.active", "clientInboundChannelExecutor"))
                .append(" 대기=").append(gaugeTagged("executor.queued", "clientInboundChannelExecutor"));
        sb.append(" | 읽음 ").append(countersByTag("chat.read.requests", "outcome"))
                .append(" 처리 ").append(timers("chat.read.duration", "outcome"))
                .append(" 스냅샷 ").append(timer(registry.find("chat.read.snapshot.duration").timer()))
                .append(" 행평균=").append(summaryMean("chat.read.snapshot.rows"));
        sb.append(" | Redis받기 대기 ").append(timer(registry.find("chat.redis.dispatch.wait").timer()))
                .append(" 처리 ").append(timers("chat.redis.dispatch.duration", "type"))
                .append(" 대기수=").append(gauge("chat.redis.dispatch.queued"));
        sb.append(" | 발행=").append(countersByTag("chat.redis.publish", "type"))
                .append(" 프레임=").append(countersByTag("chat.outbound.frames", "type"));
        return sb.toString();
    }

    private String gauge(String name) {
        Gauge g = registry.find(name).gauge();
        return g == null ? "-" : String.valueOf((long) g.value());
    }

    private String gaugeTagged(String name, String executorName) {
        Gauge g = registry.find(name).tag("name", executorName).gauge();
        return g == null ? "-" : String.valueOf((long) g.value());
    }

    private String summaryMean(String name) {
        DistributionSummary s = registry.find(name).summary();
        return s == null || s.count() == 0 ? "-" : String.format(Locale.ROOT, "%.1f", s.mean());
    }

    private long counter(String name) {
        Counter c = registry.find(name).counter();
        return c == null ? 0 : (long) c.count();
    }

    private TreeMap<String, Long> countersByTag(String name, String tag) {
        TreeMap<String, Long> out = new TreeMap<>();
        registry.find(name).counters().forEach(c -> out.put(c.getId().getTag(tag), (long) c.count()));
        return out;
    }

    private String timers(String name, String tag) {
        StringBuilder sb = new StringBuilder("{");
        registry.find(name).timers().forEach(t -> sb.append(t.getId().getTag(tag)).append('=').append(timer(t)).append(' '));
        return sb.toString().trim() + "}";
    }

    /**
     * {@code 건수 평균/p50/p95/p99/최대ms}. 백분위수가 없는 타이머(Hikari 등)는 평균·최대만.
     * 건수·평균은 서버가 뜬 뒤 누적, 백분위수·최대는 최근 한 간격 안팎.
     */
    private static String timer(Timer t) {
        if (t == null || t.count() == 0) {
            return "0건";
        }
        HistogramSnapshot snapshot = t.takeSnapshot();
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%d건 평균%.1f", t.count(),
                t.mean(TimeUnit.MILLISECONDS)));
        for (ValueAtPercentile p : snapshot.percentileValues()) {
            sb.append(String.format(Locale.ROOT, "/p%d %.1f", Math.round(p.percentile() * 100),
                    p.value(TimeUnit.MILLISECONDS)));
        }
        return sb.append(String.format(Locale.ROOT, "/최대%.1fms", t.max(TimeUnit.MILLISECONDS))).toString();
    }
}
