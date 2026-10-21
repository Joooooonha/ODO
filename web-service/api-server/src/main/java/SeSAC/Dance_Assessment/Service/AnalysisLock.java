package SeSAC.Dance_Assessment.Service;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * 분석을 <b>앱 서버 전체에서 동시에 한 건만</b> 맥미니로 보낸다(10/21 분산 구성).
 *
 * <p>분석 실행기({@code AsyncConfig})는 서버마다 동시 1건이다. 서버가 두 대가 되면 맥미니에 두 건이 겹쳐 들어가
 * 둘 다 느려지고, 분석 서버가 진행 상황을 한 건분만 들고 있어 한쪽 화면의 진행률이 비어 보인다. 그래서 PostgreSQL
 * advisory lock(세션 단위)으로 서버 사이에서도 한 건만 돌게 한다.
 *
 * <ul>
 *   <li><b>기다리는 동안 커넥션을 쥐지 않는다.</b> {@code pg_try_advisory_lock}을 {@code poll} 간격으로 다시 시도하고,
 *       잡지 못하면 커넥션을 바로 돌려준다. 잡은 쪽만 분석이 끝날 때까지 커넥션 하나를 쥔다(세션 잠금이라)</li>
 *   <li>앱이 죽거나 DB 연결이 끊기면 세션이 끝나 잠금도 풀린다. 다른 서버가 이어받는다</li>
 *   <li>풀어 주지 못한 커넥션은 풀에 돌려보내지 않고 버린다(잠금이 풀 안에 남지 않게)</li>
 *   <li>{@code wait}를 넘기면 포기하고 예외를 던진다(호출하는 쪽이 분석 실패로 기록한다)</li>
 * </ul>
 */
@Slf4j
@Component
public class AnalysisLock {

    /** 잠금 번호. 이 앱의 다른 advisory lock(Flyway 등)과 겹치지 않는 고정값 */
    static final long KEY = 0x0D0A_A1A1_5155L;

    private final DataSource dataSource;
    private final Duration poll;
    private final Duration wait;

    public AnalysisLock(DataSource dataSource,
                        @Value("${analysis.lock.poll:2s}") Duration poll,
                        @Value("${analysis.lock.wait:60m}") Duration wait) {
        this.dataSource = dataSource;
        this.poll = poll;
        this.wait = wait;
    }

    public <T> T runExclusive(String label, Callable<T> work) throws Exception {
        long deadline = System.nanoTime() + wait.toNanos();
        boolean logged = false;
        while (true) {
            Connection c = dataSource.getConnection();
            boolean locked = false;
            try {
                locked = query(c, "SELECT pg_try_advisory_lock(?)");
                if (locked) {
                    if (logged) {
                        log.info("[분석 잠금] 차례가 됨 {}", label);
                    }
                    return work.call();
                }
            } finally {
                release(c, locked);
            }
            if (!logged) {
                log.info("[분석 잠금] 다른 서버(또는 이 서버)의 분석이 끝나기를 기다림 {}", label);
                logged = true;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("분석 차례를 " + wait.toMinutes() + "분 동안 얻지 못했습니다");
            }
            Thread.sleep(poll.toMillis());
        }
    }

    private void release(Connection c, boolean locked) {
        boolean clean = true;
        if (locked) {
            try {
                clean = query(c, "SELECT pg_advisory_unlock(?)");
            } catch (SQLException e) {
                clean = false;
                log.error("[분석 잠금] 풀지 못함 — 커넥션을 버린다 원인={}", e.toString());
            }
        }
        if (!clean && dataSource instanceof HikariDataSource hikari) {
            hikari.evictConnection(c);
            return;
        }
        try {
            c.close();
        } catch (SQLException e) {
            log.warn("[분석 잠금] 커넥션 닫기 실패 원인={}", e.toString());
        }
    }

    private static boolean query(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
