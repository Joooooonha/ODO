package SeSAC.Dance_Assessment.Service.Chat;

import SeSAC.Dance_Assessment.Realtime.ReadPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 서버 쪽 읽음 모으기(10/20 작업 4, D-29·D-32·D-42~D-46). {@code chat.read.aggregate.enabled}(기본 false)로 켜고 끈다.
 * 꺼져 있으면 아무것도 하지 않고 읽음 보고는 기준선 경로(보고마다 갱신·스냅샷·발행)를 탄다.
 *
 * <p><b>받기</b>({@link #offer}): DB에 가지 않고 메모리에 (방, 사용자)별 가장 큰 값만 남긴다. 범위(방의 마지막 번호)는
 * 처리할 때 본다.
 *
 * <p><b>주기</b>: {@code period-ms}(500) 시간 기준만. 모인 게 없으면 처리하지 않는다. 처리 시점은 벽시계(epoch ms) 기준
 * {@code i × period / N}(서버 순번 i, 서버 수 N — 2대면 0ms와 250ms, D-46). {@code phase-ms}로 덮어쓸 수 있다(F1: 둘 다 0).
 *
 * <p><b>처리</b>(스레드 하나, 커넥션 하나씩, D-43):
 * <ol>
 *   <li>버퍼에서 꺼낸다 — 항목마다 {@code remove(key, value)}라 꺼내는 사이 더 큰 값이 오면 남아서 다음 주기로 간다</li>
 *   <li>한 트랜잭션: 현재 ACTIVE 멤버 행을 <b>방 번호, 사용자 번호 순으로</b> {@code FOR UPDATE}(교착 방지, I-13) →
 *       잠근 값으로 "더 큰 값이고 방의 마지막 번호 이하"만 한 문장으로 갱신 → 커밋 1번</li>
 *   <li>바뀐 방들의 스냅샷: 읽기 전용 트랜잭션 하나(커넥션 하나)에서 <b>한 문장으로</b> 읽고, <b>커넥션을 놓은 뒤</b>
 *       같은 스레드에서 파이프라인 한 번으로 발행한다(10/21 개선 — 방마다 왕복하던 것이 바뀐 방 약 320개에서 t 200ms를 넘겼다).
 *       작업 0에서 찾은 "커넥션을 쥔 채 Redis 발행"은 여기서 만들지 않는다</li>
 * </ol>
 *
 * <p><b>안전 항목</b>(D-44): 트랜잭션이 실패하면(교착·시간 제한 등) 꺼낸 값을 버퍼에 되돌린다(큰 값 유지). 종료 직전에
 * 한 번 처리한다. 갱신은 현재 ACTIVE 멤버 행에만 한다(받을 때 권한 캐시가 늦어도 쓰지 못한다).
 *
 * <p>같은 방을 두 서버가 각자 처리하는 것은 허용한다: "큰 값만 갱신"이라 결과가 같고, 스냅샷 version으로 브라우저가
 * 옛 READ를 버린다. 잠근 값이 이미 보낸 값 이상이면 다른 서버(또는 다른 탭)가 먼저 반영한 것 → {@code stale}로 센다
 * (두 서버 중복 처리의 크기).
 */
@Slf4j
@Component
public class ReadAggregator {

    /** 받은 값이 범위 밖이면 처리할 때 버린다(DB 시각 기준). */
    private static final String LOCK = """
            SELECT m.room_id, m.user_id, m.last_read_seq, r.last_seq
              FROM chat_room_member m
              JOIN unnest(?::bigint[], ?::bigint[]) AS k(room_id, user_id)
                ON k.room_id = m.room_id AND k.user_id = m.user_id
              JOIN chat_room r ON r.room_id = m.room_id
             WHERE m.status = 'ACTIVE'
             ORDER BY m.room_id, m.user_id
               FOR UPDATE OF m
            """;

    /** modified_at은 바꾸지 않는다(멤버십 변경 시각, ReadPositionWriter와 같다). 잠근 행이라 조건은 확인용 */
    private static final String ADVANCE = """
            UPDATE chat_room_member m
               SET last_read_seq = k.seq
              FROM unnest(?::bigint[], ?::bigint[], ?::bigint[]) AS k(room_id, user_id, seq)
             WHERE m.room_id = k.room_id AND m.user_id = k.user_id
               AND m.status = 'ACTIVE' AND m.last_read_seq < k.seq
            """;

    record Key(long roomId, long userId) {
    }

    private record Locked(long roomId, long userId, long current, long lastSeq) {
    }

    private final boolean enabled;
    private final long periodMs;
    private final long phaseMs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;
    private final ReadSnapshotReader reader;
    private final ReadPublisher publisher;
    private final ChatReadMetrics readMetrics;

    private final Map<Key, Long> buffer = new ConcurrentHashMap<>();
    private final AtomicBoolean dropNext = new AtomicBoolean();
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    private final Timer cycle;
    private final Timer writeHold;
    private final Timer snapshotHold;
    private final Timer lockWait;
    private final DistributionSummary entriesPerCycle;
    private final DistributionSummary roomsPerCycle;
    private final Counter commits;
    private final Counter updated;
    private final Counter stale;
    private final Counter outOfRange;
    private final Counter notMember;
    private final Counter snapshots;
    private final Counter publishes;
    private final Counter deadlocks;
    private final Counter failures;
    private final Counter dropped;
    private final Counter overruns;
    /** 측정용: 느린 주기(t ≥ SLOW_MS)의 단계별 시간과 주기 번호 */
    private static final long SLOW_MS = 100;
    private static final String[] SLOW_STAGES = {"write_acquire", "lock", "update", "commit", "snapshot_acquire", "snapshot_read", "publish", "other"};
    private final Timer[] slowStages = new Timer[SLOW_STAGES.length];
    private final DistributionSummary slowIndex;
    private final AtomicLong cycleNo = new AtomicLong();
    private long slowNanos = TimeUnit.MILLISECONDS.toNanos(SLOW_MS);
    private volatile long startedAtMs = System.currentTimeMillis();

    public ReadAggregator(@Value("${chat.read.aggregate.enabled:false}") boolean enabled,
                          @Value("${chat.read.aggregate.period-ms:500}") long periodMs,
                          @Value("${chat.read.aggregate.server-index:0}") int serverIndex,
                          @Value("${chat.read.aggregate.server-count:1}") int serverCount,
                          @Value("${chat.read.aggregate.phase-ms:-1}") long phaseOverrideMs,
                          JdbcTemplate jdbc, PlatformTransactionManager txManager,
                          ReadSnapshotReader reader, ReadPublisher publisher, ChatReadMetrics readMetrics,
                          MeterRegistry registry) {
        this.enabled = enabled;
        this.periodMs = periodMs;
        this.phaseMs = phaseOverrideMs >= 0 ? phaseOverrideMs % periodMs
                : (serverIndex * periodMs / Math.max(1, serverCount)) % periodMs;
        this.jdbc = jdbc;
        this.writeTx = new TransactionTemplate(txManager);
        this.writeTx.setTimeout(2);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.reader = reader;
        this.publisher = publisher;
        this.readMetrics = readMetrics;

        // 지표(작업 4 지표 목록). 처리 시간 t·커넥션 보유·잠금 대기는 합·횟수·최대(Prometheus에서 평균과 최대)
        this.cycle = Timer.builder("chat.read.agg.cycle").publishPercentiles(0.5, 0.95, 0.99).register(registry);
        this.writeHold = Timer.builder("chat.read.agg.connection").tag("phase", "write").register(registry);
        this.snapshotHold = Timer.builder("chat.read.agg.connection").tag("phase", "snapshot").register(registry);
        this.lockWait = Timer.builder("chat.read.agg.lock").register(registry);
        this.entriesPerCycle = DistributionSummary.builder("chat.read.agg.entries").register(registry);
        this.roomsPerCycle = DistributionSummary.builder("chat.read.agg.rooms").register(registry);
        this.commits = registry.counter("chat.read.agg.commits");
        this.updated = registry.counter("chat.read.agg.rows", "result", "updated");
        this.stale = registry.counter("chat.read.agg.rows", "result", "stale");
        this.outOfRange = registry.counter("chat.read.agg.rows", "result", "out_of_range");
        this.notMember = registry.counter("chat.read.agg.rows", "result", "not_member");
        this.snapshots = registry.counter("chat.read.agg.snapshots");
        this.publishes = registry.counter("chat.read.agg.publishes");
        this.deadlocks = registry.counter("chat.read.agg.failures", "cause", "deadlock");
        this.failures = registry.counter("chat.read.agg.failures", "cause", "other");
        this.dropped = registry.counter("chat.read.agg.dropped");
        this.overruns = registry.counter("chat.read.agg.overruns");
        for (int i = 0; i < SLOW_STAGES.length; i++) {
            this.slowStages[i] = Timer.builder("chat.read.agg.slow.stage").tag("stage", SLOW_STAGES[i]).register(registry);
        }
        this.slowIndex = DistributionSummary.builder("chat.read.agg.slow.index").register(registry);
        Gauge.builder("chat.read.agg.buffer", buffer, Map::size).register(registry);
        Gauge.builder("chat.read.agg.phase", () -> this.enabled ? this.phaseMs : -1).register(registry);
    }

    public boolean enabled() {
        return enabled;
    }

    /** 받기: 가장 큰 값만 남긴다. */
    public void offer(long roomId, long userId, long seq) {
        buffer.merge(new Key(roomId, userId), seq, Math::max);
    }

    /** 측정 전용(작업 5-4): 다음 주기에 모아 둔 값을 처리하지 않고 버린다. */
    public void dropNextCycle() {
        dropNext.set(true);
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        running = true;
        startedAtMs = System.currentTimeMillis();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "read-aggregate");
            t.setDaemon(true);
            return t;
        });
        scheduleNext();
        log.info("[읽음 모으기] 켬 주기={}ms 위상={}ms", periodMs, phaseMs);
    }

    /** 벽시계 기준 다음 처리 시점(now 이후의 첫 phase + k × period)까지 기다린다. 처리가 길면 지난 시점은 건너뛴다. */
    private void scheduleNext() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = Math.floorDiv(now - phaseMs, periodMs) * periodMs + phaseMs + periodMs;
        scheduler.schedule(this::tick, next - now, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        long started = System.currentTimeMillis();
        cycleNo.incrementAndGet();
        try {
            flush();
        } catch (RuntimeException e) {
            log.error("[읽음 모으기] 처리 실패(값은 버퍼에 되돌림) 원인={}", e.toString());
        } finally {
            if (System.currentTimeMillis() - started > periodMs) {
                overruns.increment();
            }
            scheduleNext();
        }
    }

    @PreDestroy
    void stop() {
        if (!enabled) {
            return;
        }
        running = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 종료 직전 한 번(배포·정상 종료 때 버퍼를 잃지 않게)
        try {
            flush();
            log.info("[읽음 모으기] 종료 전 처리 끝 남은 버퍼={}", buffer.size());
        } catch (RuntimeException e) {
            log.error("[읽음 모으기] 종료 전 처리 실패 남은 버퍼={} 원인={}", buffer.size(), e.toString());
        }
    }

    /** 한 주기. 테스트에서 직접 부른다. @return 바뀐 방 수 */
    int flush() {
        if (buffer.isEmpty()) {
            return 0;
        }
        long t0 = System.nanoTime();
        CycleTimes ct = new CycleTimes();
        List<Map.Entry<Key, Long>> taken = take();
        if (taken.isEmpty()) {
            return 0;
        }
        if (dropNext.compareAndSet(true, false)) {
            dropped.increment(taken.size());
            log.warn("[읽음 모으기] 측정 전용: 이번 주기 값 {}개를 처리하지 않고 버림", taken.size());
            return 0;
        }
        taken.sort(Comparator.<Map.Entry<Key, Long>>comparingLong(e -> e.getKey().roomId())
                .thenComparingLong(e -> e.getKey().userId()));
        entriesPerCycle.record(taken.size());

        Set<Long> changedRooms;
        long w0 = System.nanoTime();
        try {
            changedRooms = writeTx.execute(status -> {
                ct.writeAcquire = System.nanoTime() - w0;   // 트랜잭션 시작 = 커넥션 획득
                return write(taken, ct);
            });
            commits.increment();
        } catch (RuntimeException e) {
            giveBack(taken);
            if (isDeadlock(e)) {
                deadlocks.increment();
                log.warn("[읽음 모으기] 교착 → 버퍼에 되돌림 항목={}", taken.size());
            } else {
                failures.increment();
                log.error("[읽음 모으기] 갱신 트랜잭션 실패 → 버퍼에 되돌림 항목={} 원인={}", taken.size(), e.toString());
            }
            return 0;
        } finally {
            ct.write = System.nanoTime() - w0;
            writeHold.record(ct.write, TimeUnit.NANOSECONDS);
        }
        roomsPerCycle.record(changedRooms.size());
        if (!changedRooms.isEmpty()) {
            long s0 = System.nanoTime();
            List<ReadSnapshot> read = readTx.execute(status -> {
                ct.snapshotAcquire = System.nanoTime() - s0;
                // 10/21 개선: 바뀐 방들을 한 문장으로 읽는다(방마다 왕복하지 않는다). 방 하나 비용 지표는 나눠서 남긴다
                long q0 = System.nanoTime();
                List<ReadSnapshot> out = reader.readAll(changedRooms);
                long each = (System.nanoTime() - q0) / Math.max(1, out.size());
                for (ReadSnapshot snapshot : out) {
                    readMetrics.snapshot(each, snapshot.positions().size());
                }
                return out;
            });
            ct.snapshot = System.nanoTime() - s0;
            snapshotHold.record(ct.snapshot, TimeUnit.NANOSECONDS);
            snapshots.increment(read.size());
            long p0 = System.nanoTime();
            // 10/21 개선: 방마다 따로 발행하지 않고 파이프라인 한 번으로(같은 스레드, 커넥션을 놓은 뒤)
            publisher.publishAll(read);
            publishes.increment(read.size());
            ct.publish = System.nanoTime() - p0;
        }
        long total = System.nanoTime() - t0;
        cycle.record(total, TimeUnit.NANOSECONDS);
        if (total >= slowNanos) {
            recordSlow(total, ct, taken.size(), changedRooms.size());
        }
        return changedRooms.size();
    }

    private List<Map.Entry<Key, Long>> take() {
        List<Map.Entry<Key, Long>> taken = new ArrayList<>(buffer.size());
        for (Map.Entry<Key, Long> e : buffer.entrySet()) {
            Key key = e.getKey();
            Long seq = e.getValue();
            if (seq != null && buffer.remove(key, seq)) {
                taken.add(Map.entry(key, seq));
            }
        }
        return taken;
    }

    private void giveBack(List<Map.Entry<Key, Long>> taken) {
        for (Map.Entry<Key, Long> e : taken) {
            buffer.merge(e.getKey(), e.getValue(), Math::max);
        }
    }

    /**
     * 측정용(10/21 t 초과 원인 확인): 처리 시간이 {@code SLOW_MS} 이상인 주기만 단계별 시간과 주기 번호(기동 뒤 몇 번째)를
     * 지표({@code chat.read.agg.slow.stage}, {@code chat.read.agg.slow.index})와 로그 한 줄로 남긴다
     */
    private void recordSlow(long total, CycleTimes ct, int entries, int rooms) {
        long no = cycleNo.get();
        slowIndex.record(no);
        long commit = Math.max(0, ct.write - ct.writeAcquire - ct.lock - ct.update);
        long reads = Math.max(0, ct.snapshot - ct.snapshotAcquire);
        // 그 밖: 버퍼 꺼내기·정렬, 트랜잭션 사이 등(전체 − 갱신 트랜잭션 − 스냅샷 트랜잭션 − 발행)
        long other = Math.max(0, total - ct.write - ct.snapshot - ct.publish);
        long[] parts = {ct.writeAcquire, ct.lock, ct.update, commit, ct.snapshotAcquire, reads, ct.publish, other};
        for (int i = 0; i < SLOW_STAGES.length; i++) {
            slowStages[i].record(parts[i], TimeUnit.NANOSECONDS);
        }
        log.warn("[읽음 모으기] 느린 주기 번호={} 기동뒤={}ms t={}ms 항목={} 방={} 커넥션획득(갱신)={} 잠금={} 갱신={} 커밋={} 커넥션획득(스냅샷)={} 스냅샷조회={} 발행={} 그밖={}",
                no, System.currentTimeMillis() - startedAtMs, ms(total), entries, rooms,
                ms(ct.writeAcquire), ms(ct.lock), ms(ct.update), ms(commit), ms(ct.snapshotAcquire), ms(reads), ms(ct.publish), ms(other));
    }

    private static String ms(long nanos) {
        return String.format("%.1f", nanos / 1e6);
    }

    /** 한 주기의 단계별 시간(ns) */
    private static final class CycleTimes {
        long writeAcquire;
        long lock;
        long update;
        long write;
        long snapshotAcquire;
        long snapshot;
        long publish;
    }

    private Set<Long> write(List<Map.Entry<Key, Long>> taken, CycleTimes ct) {
        Map<Key, Long> wanted = new ConcurrentHashMap<>(taken.size() * 2);
        Long[] rooms = new Long[taken.size()];
        Long[] users = new Long[taken.size()];
        for (int i = 0; i < taken.size(); i++) {
            Key k = taken.get(i).getKey();
            rooms[i] = k.roomId();
            users[i] = k.userId();
            wanted.put(k, taken.get(i).getValue());
        }
        long l0 = System.nanoTime();
        List<Locked> locked = jdbc.query(LOCK, arrays(rooms, users),
                (rs, i) -> new Locked(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
        ct.lock = System.nanoTime() - l0;
        lockWait.record(ct.lock, TimeUnit.NANOSECONDS);
        notMember.increment(taken.size() - locked.size());

        List<Long> uRooms = new ArrayList<>();
        List<Long> uUsers = new ArrayList<>();
        List<Long> uSeqs = new ArrayList<>();
        Set<Long> changed = new LinkedHashSet<>();
        for (Locked row : locked) {
            long seq = wanted.get(new Key(row.roomId(), row.userId()));
            if (seq > row.lastSeq()) {
                outOfRange.increment();
            } else if (seq <= row.current()) {
                stale.increment();
            } else {
                uRooms.add(row.roomId());
                uUsers.add(row.userId());
                uSeqs.add(seq);
                changed.add(row.roomId());
            }
        }
        if (!uRooms.isEmpty()) {
            long u0 = System.nanoTime();
            int n = jdbc.update(ADVANCE, arrays(uRooms.toArray(Long[]::new), uUsers.toArray(Long[]::new), uSeqs.toArray(Long[]::new)));
            updated.increment(n);
            ct.update = System.nanoTime() - u0;
        }
        return changed;
    }

    private static PreparedStatementSetter arrays(Long[]... columns) {
        return ps -> {
            for (int i = 0; i < columns.length; i++) {
                ps.setArray(i + 1, ps.getConnection().createArrayOf("bigint", columns[i]));
            }
        };
    }

    private static boolean isDeadlock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** 테스트용: 느린 주기 기준을 바꾼다 */
    void slowThresholdMs(long ms) {
        this.slowNanos = TimeUnit.MILLISECONDS.toNanos(ms);
    }

    /** 테스트용 */
    int bufferSize() {
        return buffer.size();
    }

    long phaseMs() {
        return phaseMs;
    }
}
