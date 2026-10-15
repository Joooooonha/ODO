package SeSAC.Dance_Assessment.Dto.User;

import SeSAC.Dance_Assessment.Domain.User;

/**
 * 로그인 후 또는 /users/me에서 사용자 정보를 반환할 때 사용.
 *
 * <p>팀 정보(teamId, teamName, isLeader)는 뺐다. 한 사용자가 여러 팀에 속하므로
 * "내 팀" 하나를 말할 수 없다. 팀 정보는 TEAM 탭의 {@code GET /teams}로 본다.
 * 배포 전에 열려 있던 옛 화면(Header, Dashboard)은 이 필드가 없으면 팀 표시를
 * 건너뛸 뿐 깨지지 않는다.
 */
public record UserResponse(
        Long id,
        String loginId,
        String nickname
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getLoginId(),
                user.getNickname());
    }
}
