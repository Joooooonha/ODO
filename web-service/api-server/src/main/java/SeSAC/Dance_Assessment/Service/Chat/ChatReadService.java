package SeSAC.Dance_Assessment.Service.Chat;

import SeSAC.Dance_Assessment.Common.ApiException;
import SeSAC.Dance_Assessment.Common.ErrorCode;
import SeSAC.Dance_Assessment.Dto.Chat.ReadStateResponse;
import SeSAC.Dance_Assessment.Realtime.ReadPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 읽음 표시 기준선(Task-chat-read 3절). <b>보고를 받을 때마다 바로 한 번씩</b> 처리한다 — 묶기·줄이기·미루기
 * (디바운스·배치·합치기·변경분만·보는 사람만)는 넣지 않는다. 그것들은 측정 뒤 개선 후보다.
 *
 * <p>순서: ① 갱신(한 문장, 트랜잭션) → ② 커밋 → ③ 바뀐 행이 1일 때만 스냅샷 조회(별도 읽기 전용 트랜잭션) → 발행.
 * 커밋 이벤트 리스너({@code AFTER_COMMIT})로 하지 않는다 — 그 안에서 DB를 쓰면 끝난 트랜잭션의 커넥션에
 * 묶이는 함정이 있다. 순서대로 부르면 그 문제가 없고 결과는 같다(단계 1 A1).
 *
 * <p>이 클래스에는 {@code @Transactional}을 붙이지 않는다(스냅샷은 갱신이 커밋된 뒤의 별도 트랜잭션이어야 한다).
 * DB 접근은 모두 트랜잭션 메서드(ReadPositionWriter·ReadSnapshotReader)를 거친다 — 요청 하나가 커넥션 하나만
 * 쓰게 하는 규칙이다(ReadSnapshotReader 설명).
 */
@Service
@RequiredArgsConstructor
public class ChatReadService {

    private final ReadPositionWriter writer;
    private final ReadSnapshotReader reader;
    private final ReadPublisher publisher;
    private final ChatReadMetrics metrics;

    /**
     * 읽음 보고. 사용자는 인증 정보에서만 온다.
     *
     * @throws ApiException 400 {@code INVALID_READ_SEQ}·{@code READ_SEQ_AHEAD} / 403 {@code NOT_CHAT_MEMBER}
     *                      (방이 없거나, 멤버가 아니거나, 나간 멤버)
     */
    public void report(long me, long roomId, Long seq) {
        long start = System.nanoTime();
        String outcome = ChatReadMetrics.INVALID;
        try {
            if (seq == null || seq < 0) {
                throw new ApiException(ErrorCode.INVALID_READ_SEQ);
            }
            if (writer.advance(roomId, me, seq) == 1) {
                outcome = ChatReadMetrics.ADVANCED;
                publisher.publish(snapshot(roomId));
                return;
            }
            // 바뀐 행이 0: 멤버가 아니면(방이 없어도) 403이 먼저다 — 방 번호로 방이 있는지 알 수 없게
            List<Object[]> rows = reader.memberStatus(roomId, me);
            if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0)[0])) {
                outcome = ChatReadMetrics.FORBIDDEN;
                throw new ApiException(ErrorCode.NOT_CHAT_MEMBER);
            }
            if (seq > (long) rows.get(0)[1]) {
                throw new ApiException(ErrorCode.READ_SEQ_AHEAD);
            }
            outcome = ChatReadMetrics.NOOP;   // 같거나 작은 값: 스냅샷·발행 없이 끝
        } finally {
            metrics.request(outcome, System.nanoTime() - start);
        }
    }

    /** 방을 열 때·재연결할 때의 읽음 상태. 방송과 같은 계산. */
    public ReadStateResponse readState(long me, long roomId) {
        List<Object[]> rows = reader.memberStatus(roomId, me);
        if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0)[0])) {
            throw new ApiException(ErrorCode.NOT_CHAT_MEMBER);
        }
        return ReadStateResponse.of(snapshot(roomId));
    }

    ReadSnapshot snapshot(long roomId) {
        long start = System.nanoTime();
        ReadSnapshot snapshot = reader.read(roomId);
        metrics.snapshot(System.nanoTime() - start, snapshot.positions().size());
        return snapshot;
    }
}
