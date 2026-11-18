package com.odo.team.domain;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository {

	TeamMember save(TeamMember member);

	/** unique (team_id, account_id) 인덱스로 찾는다. */
	Optional<TeamMember> findByTeamIdAndAccountId(Long teamId, Long accountId);

	Optional<TeamMember> findByTeamIdAndRole(Long teamId, TeamRole role);

	List<TeamMember> findAllByTeamId(Long teamId);

	/** 내 팀 목록. 들어온 순서. */
	List<TeamMember> findAllByAccountIdOrderByJoinedAtAscIdAsc(Long accountId);

}
