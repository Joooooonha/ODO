package SeSAC.Dance_Assessment.Service.Chat;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 읽음 스냅샷 조회 — 갱신이 커밋된 <b>뒤의 별도 트랜잭션</b>(읽기 전용).
 *
 * <p><b>왜 트랜잭션 안인가(커넥션 하나 규칙).</b> HTTP 요청에는 OSIV(open-in-view, 기본 켜짐)가 EntityManager를
 * 요청 끝까지 열어 두고, Spring의 Hibernate 설정은 그 EntityManager가 한 번 잡은 커넥션을 요청이 끝날 때까지
 * 쥔다({@code DELAYED_ACQUISITION_AND_HOLD}, HibernateJpaVendorAdapter 확인). 갱신 트랜잭션 뒤에 트랜잭션 없이
 * JdbcTemplate을 부르면 <b>두 번째 커넥션</b>을 풀에서 받는다 → 동시 보고가 풀 크기(10)에 가까워지면 모두가 첫
 * 커넥션을 쥔 채 두 번째를 기다려 30초 획득 시간 제한까지 멈췄다(부하 생성기 연기 실행에서 발견, 회귀 테스트
 * {@code ChatReadTest.concurrentReportsBeyondPoolSizeDoNotStarve}). 트랜잭션으로 부르면 그 쥔 커넥션을 다시 쓴다.
 *
 * <p>{@code version}은 이 문장의 시작 시각({@code statement_timestamp()}) — 트랜잭션 시작 시각이 아니다.
 */
@Component
@RequiredArgsConstructor
public class ReadSnapshotReader {

    /**
     * 활동 멤버 전원의 위치와, 이 문장의 시작 시각(DB 시계) — 한 문장이라 모든 행이 같은 시점을 본다.
     * {@code clock_timestamp()}는 행마다 달라질 수 있어 쓰지 않는다. 멤버가 없어도 version은 나오게 한 줄을 둔다.
     */
    private static final String SNAPSHOT = """
            SELECT (extract(epoch FROM statement_timestamp()) * 1000000)::bigint AS version,
                   m.user_id, m.last_read_seq
              FROM (VALUES (1)) AS one(x)
              LEFT JOIN chat_room_member m ON m.room_id = ? AND m.status = 'ACTIVE'
             ORDER BY m.user_id
            """;

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public ReadSnapshot read(long roomId) {
        long[] version = {0};
        List<ReadSnapshot.Position> positions = new ArrayList<>();
        jdbc.query(SNAPSHOT, rs -> {
            version[0] = rs.getLong("version");
            long userId = rs.getLong("user_id");
            if (!rs.wasNull()) {
                positions.add(new ReadSnapshot.Position(userId, rs.getLong("last_read_seq")));
            }
        }, roomId);
        return new ReadSnapshot(roomId, version[0], List.copyOf(positions));
    }

    /** 0행일 때 이유 찾기·read-state 권한 확인도 같은 이유로 트랜잭션 안에서. {@code [status, last_seq]}, 방이 없으면 빈 목록. */
    @Transactional(readOnly = true)
    public List<Object[]> memberStatus(long roomId, long userId) {
        return jdbc.query("""
                SELECT m.status, r.last_seq
                  FROM chat_room r
                  LEFT JOIN chat_room_member m ON m.room_id = r.room_id AND m.user_id = ?
                 WHERE r.room_id = ?
                """, (rs, i) -> new Object[]{rs.getString("status"), rs.getLong("last_seq")}, userId, roomId);
    }
}
