package SeSAC.Dance_Assessment.Dto.Mission;

/**
 * 제출. 영상은 먼저 업로드({@code purpose: MISSION}, {@code type: PRACTICE})해 둔 것.
 *
 * @param content 선택
 */
public record SubmissionCreateRequest(
        Long videoId,
        String content) {
}
