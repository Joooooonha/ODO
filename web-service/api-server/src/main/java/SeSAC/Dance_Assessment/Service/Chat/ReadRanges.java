package SeSAC.Dance_Assessment.Service.Chat;

import java.util.ArrayList;
import java.util.List;

/**
 * 읽음 위치 → "메시지 번호별 안 읽은 사람 수" 구간. 계산은 서버 한 곳(여기)에서만 한다.
 *
 * <p>번호 s의 안 읽은 사람 수 = 활동 멤버 중 {@code lastReadSeq < s}인 사람 수. 보낸 사람도 똑같이 센다
 * (보낸 사람의 읽음은 그 브라우저의 일반 읽음 보고로 처리된다).
 *
 * <p>위치를 오름차순 p1 ≤ … ≤ pN으로 놓으면 s ≤ p1 → 0명, p(k) < s ≤ p(k+1) → k명, s > pN → N명.
 * 같은 위치는 하나로 합친다. 표현: {@code [{toSeq: q1, unread: 0}, {toSeq: q2, unread: c1}, …,
 * {toSeq: null, unread: N}]} — s가 들어가는 첫 구간({@code s ≤ toSeq}, 마지막은 끝없음)의 값이 답이다.
 */
public final class ReadRanges {

    /** @param toSeq 이 구간의 마지막 번호(포함). null이면 끝없음 */
    public record Range(Long toSeq, int unread) {
    }

    private ReadRanges() {
    }

    public static List<Range> of(List<ReadSnapshot.Position> positions) {
        long[] sorted = positions.stream().mapToLong(ReadSnapshot.Position::lastReadSeq).sorted().toArray();
        List<Range> ranges = new ArrayList<>();
        int i = 0;
        while (i < sorted.length) {
            long p = sorted[i];
            // p 이하 번호는 p보다 작은 위치의 사람(i명)이 안 읽었다
            ranges.add(new Range(p, i));
            while (i < sorted.length && sorted[i] == p) {
                i++;
            }
        }
        ranges.add(new Range(null, sorted.length));
        return ranges;
    }

    /** 번호 s의 안 읽은 사람 수(테스트·검증용). 화면도 같은 방법으로 찾는다. */
    public static int unreadAt(List<Range> ranges, long seq) {
        for (Range r : ranges) {
            if (r.toSeq() == null || seq <= r.toSeq()) {
                return r.unread();
            }
        }
        throw new IllegalStateException("마지막 구간이 없음");
    }
}
