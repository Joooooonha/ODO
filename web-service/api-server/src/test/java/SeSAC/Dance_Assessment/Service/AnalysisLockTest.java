package SeSAC.Dance_Assessment.Service;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 분석 잠금(10/21 분산 구성): 앱 서버 두 대(같은 DB의 잠금 두 개)에서 동시에 분석을 시작해도 맥미니에는 한 건씩만 간다.
 * 기다리는 쪽은 커넥션을 쥐지 않는다.
 */
@SpringBootTest
@ActiveProfiles("dev")
class AnalysisLockTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("두 서버가 동시에 분석해도 한 번에 한 건만 돈다 — 둘 다 끝나고, 끝나면 잠금이 남지 않는다")
    void onlyOneAtATimeAcrossServers() throws Exception {
        AnalysisLock serverA = new AnalysisLock(dataSource, Duration.ofMillis(50), Duration.ofSeconds(20));
        AnalysisLock serverB = new AnalysisLock(dataSource, Duration.ofMillis(50), Duration.ofSeconds(20));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                AnalysisLock lock = i % 2 == 0 ? serverA : serverB;
                String label = "job-" + i;
                results.add(pool.submit(() -> lock.runExclusive(label, () -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    Thread.sleep(150);
                    running.decrementAndGet();
                    return label;
                })));
            }
            for (Future<String> f : results) {
                assertThat(f.get(30, TimeUnit.SECONDS)).startsWith("job-");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxRunning.get()).isEqualTo(1);
        // bigint 잠금은 pg_locks에 (classid = 위 32비트, objid = 아래 32비트, objsubid = 1)로 보인다
        String heldSql = "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objsubid = 1"
                + " AND ((classid::bigint << 32) | objid::bigint) = ?";
        Integer held = jdbc.queryForObject(heldSql, Integer.class, AnalysisLock.KEY);
        assertThat(held).isZero();
    }

    @Test
    @DisplayName("기다리는 동안 커넥션을 쥐지 않고, 정해진 시간을 넘기면 포기한다. 작업이 실패해도 잠금은 풀린다")
    void waiterHoldsNoConnectionAndGivesUp() throws Exception {
        AnalysisLock holder = new AnalysisLock(dataSource, Duration.ofMillis(50), Duration.ofSeconds(10));
        AnalysisLock waiter = new AnalysisLock(dataSource, Duration.ofMillis(50), Duration.ofMillis(600));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = pool.submit(() -> holder.runExclusive("holder", () -> {
                inside.countDown();
                finish.await(10, TimeUnit.SECONDS);
                return null;
            }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objsubid = 1"
                    + " AND ((classid::bigint << 32) | objid::bigint) = ?", Integer.class, AnalysisLock.KEY)).isEqualTo(1);
            int activeBefore = ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
            assertThatThrownBy(() -> waiter.runExclusive("waiter", () -> "never"))
                    .isInstanceOf(IllegalStateException.class);
            // 기다린 쪽은 커넥션을 돌려줬다(잡고 있는 것은 holder의 하나뿐)
            assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections()).isLessThanOrEqualTo(activeBefore);
            finish.countDown();
            holding.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThatThrownBy(() -> holder.runExclusive("fails", () -> {
            throw new IllegalArgumentException("분석 실패");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(waiter.runExclusive("after", () -> "ok")).isEqualTo("ok");
    }
}
