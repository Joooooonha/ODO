package SeSAC.Dance_Assessment.Realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 제어 이벤트 유실 대비: 서버마다 5초마다 최근에 바뀐 채팅 멤버를 DB에서 다시 읽어 허용 방 캐시를 고친다(6.4).
 *
 * <pre>
 * SELECT room_id, user_id, status FROM chat_room_member WHERE modified_at > (지난 확인 시각 − 30초)
 * </pre>
 *
 * <ul>
 *   <li>결과 중 <b>이 서버에 연결된 사용자</b>만 메모리에서 골라 적용한다({@code IN (접속자)}를 쓰지 않는다)</li>
 *   <li>30초 겹쳐 읽는 이유: {@code modified_at}은 문장 실행 시각(트랜잭션 시작 시각)이고 커밋은 그보다
 *       늦다. 아직 커밋 안 된 변경을 다음 확인이 놓치지 않게 한다. 같은 변경을 두 번 적용해도 결과가 같다</li>
 *   <li><b>시각은 DB 시계로 잰다.</b> {@code modified_at}도 DB가 찍으므로(now()) 서버 2대의 시계가 달라도
 *       기준이 하나다</li>
 *   <li>재확인이 실제로 고친 것은 이벤트보다 먼저 발견한 변경(= 이벤트 유실·지연)이라 지표로 센다.
 *       이벤트가 오는 몇 ms 사이에 재확인이 돌면 유실이 아닌데도 셀 수 있다 — 드물다</li>
 * </ul>
 */
@Slf4j
@Component
public class AccessRecheck {

    static final String OVERLAP = "30 seconds";

    private final JdbcTemplate jdbc;
    private final ChatConnectionRegistry registry;
    private final ChatAccessControl access;
    private final ChatRealtimeMetrics metrics;

    private OffsetDateTime lastCheckedAt;

    public AccessRecheck(JdbcTemplate jdbc, ChatConnectionRegistry registry, ChatAccessControl access,
                         ChatRealtimeMetrics metrics) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.access = access;
        this.metrics = metrics;
    }

    private record Change(long roomId, long userId, String status) {
    }

    @Scheduled(fixedDelayString = "${chat.access.recheck-interval:5s}")
    public void scheduled() {
        try {
            recheck();
        } catch (RuntimeException e) {
            log.warn("[권한 재확인 실패] 다음 주기에 다시 한다 원인={}", e.toString());
        }
    }

    /** @return 고친 세션 수 */
    public synchronized int recheck() {
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        OffsetDateTime since = lastCheckedAt == null ? now : lastCheckedAt;
        List<Change> changes = jdbc.query(
                "SELECT room_id, user_id, status FROM chat_room_member"
                        + " WHERE modified_at > ?::timestamptz - INTERVAL '" + OVERLAP + "'",
                (rs, i) -> new Change(rs.getLong(1), rs.getLong(2), rs.getString(3)), since);
        lastCheckedAt = now;

        int corrected = 0;
        for (Change change : changes) {
            if (registry.ofUser(change.userId()).isEmpty()) {
                continue;
            }
            if ("ACTIVE".equals(change.status())) {
                int n = access.grant(change.userId(), change.roomId());
                metrics.recheckCorrected(ControlEvent.GRANTED, n);
                corrected += n;
            } else {
                int n = access.revoke(change.userId(), change.roomId());
                metrics.recheckCorrected(ControlEvent.REVOKED, n);
                corrected += n;
            }
        }
        if (corrected > 0) {
            log.warn("[권한 재확인] 이벤트보다 먼저 발견한 변경 {}건을 고침(제어 이벤트 유실·지연)", corrected);
        }
        return corrected;
    }
}
