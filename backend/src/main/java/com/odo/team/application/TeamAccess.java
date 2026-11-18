package com.odo.team.application;

import com.odo.team.domain.TeamMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 팀원인지, 팀장인지 DB로 확인한다. 02_TeamSpace_spec.md의 "역할과 권한 확인".
 * 팀 스페이스 안의 API가 모두 맨 처음에 부르고, 공연 같은 다른 기능도 쓴다.
 *
 * 사용자 담당. 시그니처는 spec의 "사용자 담당 시그니처"를 따르고 본문은 사용자가 쓴다.
 */
@Component
@RequiredArgsConstructor
public class TeamAccess {

	/**
	 * 팀원이면 그 팀원 행을 돌려준다.
	 * 팀원이 아니거나 팀이 없으면 TeamNotFoundException(404). 403을 주면 그 ID의 팀이 있다는 것이 드러난다.
	 */
	public TeamMember requireMember(long teamId, long accountId) {
		throw new UnsupportedOperationException("사용자 구현 예정");
	}

	/**
	 * 팀장이면 그 팀원 행을 돌려준다.
	 * 팀원이 아니면 TeamNotFoundException(404), 팀원이지만 팀장이 아니면 NotTeamLeaderException(403).
	 */
	public TeamMember requireLeader(long teamId, long accountId) {
		throw new UnsupportedOperationException("사용자 구현 예정");
	}

}
