package SeSAC.Dance_Assessment.Service.Chat;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 읽음 위치 갱신 — 한 문장, 한 트랜잭션. 기본 커넥션 풀·JPA 트랜잭션 매니저를 메시지 저장과 나눠 쓴다
 * (기준선의 사실, 측정 대상).
 *
 * <ul>
 *   <li><b>더 큰 값일 때만</b>({@code last_read_seq < :seq}): 휴대폰 50 → PC 40이 늦게 와도 줄지 않는다.
 *       같은 행을 동시에 갱신하면 늦은 쪽이 앞 커밋을 기다렸다가 조건을 다시 본다(READ COMMITTED) → 최댓값이 남는다</li>
 *   <li><b>그 방의 마지막 번호 이하일 때만</b>: 아직 없는 메시지는 읽을 수 없다. {@code last_seq}는 줄지 않으므로
 *       잠그지 않고 읽어도 된다</li>
 *   <li><b>{@code modified_at}을 바꾸지 않는다.</b> 멤버십 변경 시각이고, 5초 재확인이 이 값으로 변경을 찾는다</li>
 * </ul>
 *
 * 바뀐 행이 0이면 왜인지는 부른 쪽이 따로 묻는다(403·400·noop) — 정상 경로는 이 한 문장뿐이다.
 */
@Component
@RequiredArgsConstructor
public class ReadPositionWriter {

    private static final String ADVANCE = """
            UPDATE chat_room_member
               SET last_read_seq = ?
             WHERE room_id = ? AND user_id = ? AND status = 'ACTIVE'
               AND last_read_seq < ?
               AND ? <= (SELECT last_seq FROM chat_room WHERE room_id = ?)
            """;

    private final JdbcTemplate jdbc;

    /** @return 바뀐 행 수(0 또는 1) */
    @Transactional
    public int advance(long roomId, long userId, long seq) {
        return jdbc.update(ADVANCE, seq, roomId, userId, seq, seq, roomId);
    }
}
