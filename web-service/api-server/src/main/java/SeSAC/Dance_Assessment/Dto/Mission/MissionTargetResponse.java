package SeSAC.Dance_Assessment.Dto.Mission;

/**
 * 제출 현황의 한 줄. 나가거나 추방된 대상자도 별도 표시 없이 그대로 들어 있다.
 *
 * @param submissionId 미제출이면 null
 */
public record MissionTargetResponse(
        Long userId,
        String nickname,
        Long submissionId) {
}
