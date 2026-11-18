package com.odo.team.application;

import com.odo.team.domain.NotTeamLeaderException;
import com.odo.team.domain.TeamMember;
import com.odo.team.domain.TeamMemberRepository;
import com.odo.team.domain.TeamNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 팀원인지, 팀장인지 확인한다. 02_TeamSpace_spec.md의 "권한 확인".
 * 요청마다 DB에서 확인한다. 팀 스페이스뿐 아니라 공연 같은 다른 기능도 이것을 쓴다.
 * 호출하는 쪽의 트랜잭션 안에서 돌고, 돌려준 팀원 행은 그 트랜잭션의 영속 상태다.
 */
@Component
@RequiredArgsConstructor
public class TeamAccess {

	private final TeamMemberRepository teamMemberRepository;

	/**
	 * 팀원이면 그 팀원 행을 돌려준다.
	 * 팀원이 아니거나 팀이 없으면 TeamNotFoundException(404). 403을 주면 그 ID의 팀이 있다는 것이 드러난다.
	 */
	public TeamMember requireMember(long teamId, long accountId) {
		return teamMemberRepository.findByTeamIdAndAccountId(teamId, accountId)
			.orElseThrow(TeamNotFoundException::new);
	}

	/**
	 * 팀장이면 그 팀원 행을 돌려준다.
	 * 팀원이 아니면 TeamNotFoundException(404), 팀원이지만 팀장이 아니면 NotTeamLeaderException(403).
	 * 팀원 행에 역할이 있으므로 팀장을 따로 조회하지 않는다.
	 */
	public TeamMember requireLeader(long teamId, long accountId) {
		TeamMember member = requireMember(teamId, accountId);
		if (!member.isLeader()) {
			throw new NotTeamLeaderException();
		}
		return member;
	}

}
