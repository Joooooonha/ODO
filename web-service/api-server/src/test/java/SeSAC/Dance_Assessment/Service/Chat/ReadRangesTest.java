package SeSAC.Dance_Assessment.Service.Chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 구간 계산(3.3) 단위 테스트 — 번호 s의 안 읽은 사람 수 = 위치 < s인 활동 멤버 수. */
class ReadRangesTest {

    private static List<ReadSnapshot.Position> positions(long... seqs) {
        return Arrays.stream(seqs).mapToObj(s -> new ReadSnapshot.Position(1000 + s, s)).toList();
    }

    /** 0..max+2 모든 번호에서 정의대로 센 값과 같은지. */
    private static void assertMatchesDefinition(List<ReadSnapshot.Position> positions) {
        List<ReadRanges.Range> ranges = ReadRanges.of(positions);
        long max = positions.stream().mapToLong(ReadSnapshot.Position::lastReadSeq).max().orElse(0);
        for (long s = 0; s <= max + 2; s++) {
            long seq = s;
            long expected = positions.stream().filter(p -> p.lastReadSeq() < seq).count();
            assertThat(ReadRanges.unreadAt(ranges, s)).as("seq %d, positions %s", s, positions).isEqualTo((int) expected);
        }
    }

    @Test
    @DisplayName("위치가 모두 같을 때: 그 위치까지 0명, 그 뒤는 전원")
    void allSame() {
        List<ReadRanges.Range> ranges = ReadRanges.of(positions(5, 5, 5));
        assertThat(ranges).containsExactly(new ReadRanges.Range(5L, 0), new ReadRanges.Range(null, 3));
    }

    @Test
    @DisplayName("위치가 모두 다를 때: 구간이 위치마다 하나씩, 한 명씩 늘어난다")
    void allDifferent() {
        List<ReadRanges.Range> ranges = ReadRanges.of(positions(7, 2, 4));
        assertThat(ranges).containsExactly(
                new ReadRanges.Range(2L, 0), new ReadRanges.Range(4L, 1), new ReadRanges.Range(7L, 2),
                new ReadRanges.Range(null, 3));
        assertMatchesDefinition(positions(7, 2, 4));
    }

    @Test
    @DisplayName("위치 0(아무것도 안 읽음·새 DM)이 섞여도 정의와 같다")
    void zeroPosition() {
        assertMatchesDefinition(positions(0, 0, 3));
        assertThat(ReadRanges.unreadAt(ReadRanges.of(positions(0, 3)), 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 위치는 하나로 합친다")
    void duplicatesMerge() {
        List<ReadRanges.Range> ranges = ReadRanges.of(positions(3, 3, 8, 8, 8, 10));
        assertThat(ranges).containsExactly(
                new ReadRanges.Range(3L, 0), new ReadRanges.Range(8L, 2), new ReadRanges.Range(10L, 5),
                new ReadRanges.Range(null, 6));
    }

    @Test
    @DisplayName("보낸 사람도 똑같이 센다: 보낸 사람 위치가 자기 메시지보다 작으면 그 메시지에서 1명으로 센다")
    void senderCounted() {
        // 보낸 사람(위치 9)이 10번을 보냈고 아직 자기 읽음 보고 전, 다른 두 명은 10까지 읽음
        assertThat(ReadRanges.unreadAt(ReadRanges.of(positions(9, 10, 10)), 10)).isEqualTo(1);
    }

    @Test
    @DisplayName("새로 들어온 멤버(가입 시점 위치 = 그때의 last_seq): 그 전 메시지는 읽은 것으로, 뒤 메시지부터 센다")
    void newMemberStartsAtJoinPosition() {
        List<ReadRanges.Range> ranges = ReadRanges.of(positions(20, 20, 15));   // 15번 시점에 들어옴
        assertThat(ReadRanges.unreadAt(ranges, 15)).isZero();
        assertThat(ReadRanges.unreadAt(ranges, 16)).isEqualTo(1);
        assertThat(ReadRanges.unreadAt(ranges, 21)).isEqualTo(3);
    }

    @Test
    @DisplayName("멤버가 없으면 구간은 끝없는 0명 하나")
    void empty() {
        assertThat(ReadRanges.of(List.of())).containsExactly(new ReadRanges.Range(null, 0));
    }

    @Test
    @DisplayName("무작위 1,000회: 정의(위치 < s인 수)와 항상 같다")
    void randomMatchesDefinition() {
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            long[] seqs = new long[1 + random.nextInt(20)];
            for (int k = 0; k < seqs.length; k++) {
                seqs[k] = random.nextInt(15);
            }
            assertMatchesDefinition(positions(seqs));
        }
    }
}
