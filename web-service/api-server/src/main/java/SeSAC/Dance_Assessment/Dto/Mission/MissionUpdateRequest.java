package SeSAC.Dance_Assessment.Dto.Mission;

/**
 * 미션 수정 — 세 필드를 통째로 받는다. 안내 텍스트를 비우려면 null(또는 빈 문자열).
 * 대상자 목록은 받지 않는다(생성 후 수정 불가).
 */
public record MissionUpdateRequest(
        String title,
        String description,
        Long referenceVideoId) {
}
