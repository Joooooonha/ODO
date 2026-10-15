package SeSAC.Dance_Assessment.Dto.Mission;

import SeSAC.Dance_Assessment.Domain.TeamRole;

import java.time.Instant;
import java.util.List;

/**
 * 미션 상세. 레퍼런스 영상은 {@code GET /videos/{referenceVideoId}}로 재생한다.
 *
 * @param myRole   보는 사람의 팀 역할 — 팀장이면 [수정]·[삭제]를 그린다
 * @param myStatus 대상자가 아니면 null
 */
public record MissionDetailResponse(
        Long missionId,
        Long teamId,
        String title,
        String description,
        Long referenceVideoId,
        String writerNickname,
        Instant createdAt,
        TeamRole myRole,
        MissionSubmitStatus myStatus,
        List<MissionTargetResponse> targets) {
}
