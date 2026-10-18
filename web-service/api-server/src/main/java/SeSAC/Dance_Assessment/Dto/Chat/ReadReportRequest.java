package SeSAC.Dance_Assessment.Dto.Chat;

/**
 * 읽음 보고 본문. 사용자는 받지 않는다 — 인증 정보(쿠키 JWT)에서 꺼낸다. 본문에 다른 칸(userId 등)이
 * 있어도 버려진다(남의 읽음 위치 조작 방지).
 *
 * @param seq 빈틈 없이 이어진 마지막 번호(화면의 contiguousTo)
 */
public record ReadReportRequest(Long seq) {
}
