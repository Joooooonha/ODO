package SeSAC.Dance_Assessment.Dto.Team;

/**
 * 참여 전 확인 화면 ("OOO 팀에 참여할까요?").
 *
 * <p>팀 id는 주지 않는다. 확인 단계에는 필요 없고, 참여가 끝나면 참여 응답이 준다.
 */
public record JoinPreviewResponse(
        String name,
        String leaderNickname,
        long memberCount) {
}
