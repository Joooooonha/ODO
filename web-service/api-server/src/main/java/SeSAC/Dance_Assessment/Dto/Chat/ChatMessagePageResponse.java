package SeSAC.Dance_Assessment.Dto.Chat;

import java.util.List;

/**
 * 메시지 조회 결과. {@code messages}는 항상 seq 오름차순이다.
 *
 * @param lastSeq 조회 시점의 방 마지막 번호. 브라우저가 "이전 메시지 더 보기"와 빈틈을 판단하는 데 쓴다
 */
public record ChatMessagePageResponse(long roomId, long lastSeq, List<ChatMessageResponse> messages) {
}
