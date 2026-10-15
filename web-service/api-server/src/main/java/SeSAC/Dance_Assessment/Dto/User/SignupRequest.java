package SeSAC.Dance_Assessment.Dto.User;

/**
 * 회원가입 요청 (개발 환경 전용).
 *
 * <p>팀 선택·생성은 없다. 팀은 TEAM 탭에서만 만들고 참여한다.
 */
public record SignupRequest(
        String loginId, // 로그인 아이디
        String password, // 비밀번호
        String nickname // 닉네임
) {
}
