package SeSAC.Dance_Assessment.Dto.Team;

/**
 * 참여 코드. 쿼리스트링이 아니라 본문으로 받는다 — URL에 실으면 Nginx 액세스 로그에 남는다.
 */
public record JoinCodeRequest(
        String code) {
}
