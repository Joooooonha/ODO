package SeSAC.Dance_Assessment.Dto.Mission;

import java.util.List;

/**
 * 미션 생성. 레퍼런스 영상은 먼저 업로드({@code purpose: MISSION}, {@code type: REFERENCE})해 둔 것.
 *
 * @param targetUserIds 대상자. 그 팀의 활동 중 팀원(팀장 제외) 1명 이상
 */
public record MissionCreateRequest(
        String title,
        String description,
        Long referenceVideoId,
        List<Long> targetUserIds) {
}
