package SeSAC.Dance_Assessment.Dto.Mission;

import java.time.Instant;

/**
 * 미션 탭의 한 항목: 제목 → 제출 현황(submittedCount/targetCount) → 내 상태 배지.
 *
 * @param myStatus 내가 대상자가 아니면 null — 화면은 배지를 그리지 않는다
 */
public record MissionSummaryResponse(
        Long missionId,
        String title,
        long submittedCount,
        long targetCount,
        MissionSubmitStatus myStatus,
        Instant createdAt) {
}
