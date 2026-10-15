package SeSAC.Dance_Assessment.Dto.Mission;

import java.time.Instant;
import java.util.List;

/**
 * 제출물 보기 — 영상({@code GET /videos/{videoId}}), 텍스트, 코멘트.
 *
 * @param canComment 보는 사람이 팀장이거나 이 제출물의 제출자면 true — 입력란을 그린다
 */
public record SubmissionDetailResponse(
        Long submissionId,
        Long missionId,
        Long submitterUserId,
        String submitterNickname,
        Long videoId,
        String content,
        Instant createdAt,
        boolean canComment,
        List<CommentResponse> comments) {
}
