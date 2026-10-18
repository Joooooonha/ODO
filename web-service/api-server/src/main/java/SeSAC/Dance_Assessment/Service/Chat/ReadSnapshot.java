package SeSAC.Dance_Assessment.Service.Chat;

import java.util.List;

/**
 * 한 방의 활동 멤버 전원의 읽음 위치를 한 번에 읽은 것.
 *
 * <p>{@code version}은 그 SELECT 문장의 시작 시각({@code statement_timestamp()}, DB 시계)을 epoch
 * 마이크로초로 바꾼 값이다. 읽음 위치는 줄지 않고 SELECT 한 문장은 시작 시점의 일관된 모습을 보므로,
 * 나중에 시작한 조회는 모든 사용자에 대해 같거나 큰 위치를 본다 → 받는 쪽은 version이 작은 것을 버린다.
 * 밀리초로 자르면 같은 밀리초의 두 스냅샷을 구분하지 못한다(JS 정수 한계 2^53 안에 들어간다).
 */
public record ReadSnapshot(long roomId, long version, List<Position> positions) {

    public record Position(long userId, long lastReadSeq) {
    }
}
