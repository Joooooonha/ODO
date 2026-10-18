package SeSAC.Dance_Assessment.Dto.Chat;

import SeSAC.Dance_Assessment.Service.Chat.ReadRanges;
import SeSAC.Dance_Assessment.Service.Chat.ReadSnapshot;

import java.util.List;

/**
 * 방의 읽음 상태(방을 열 때·재연결할 때). 방송되는 READ 프레임과 같은 계산 결과다.
 *
 * @param version 스냅샷 조회 문장의 시작 시각(DB 시계, epoch 마이크로초). 클수록 새것
 */
public record ReadStateResponse(long version, List<ReadSnapshot.Position> positions, List<ReadRanges.Range> ranges) {

    public static ReadStateResponse of(ReadSnapshot snapshot) {
        return new ReadStateResponse(snapshot.version(), snapshot.positions(), ReadRanges.of(snapshot.positions()));
    }
}
