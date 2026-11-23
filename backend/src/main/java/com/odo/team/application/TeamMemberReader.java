package com.odo.team.application;

import java.util.HashMap;
import java.util.Map;

import com.odo.team.domain.entity.TeamMember;
import com.odo.team.domain.entity.TeamRole;
import com.odo.team.domain.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 팀원 테이블의 값을 읽는다. 02_TeamSpace_spec.md의 "구성 요소".
 * 권한은 확인하지 않는다(TeamAccess의 일). 팀 스페이스뿐 아니라 공연 같은 다른 기능도 이것을 쓴다.
 * 호출하는 쪽의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class TeamMemberReader {

	private final TeamMemberRepository teamMemberRepository;

	/** 그 팀 팀원들의 지금 기수. 기수가 없는 팀원도 있다. Collectors.toMap은 null 값을 받지 않아 직접 모은다. */
	public Map<Long, Integer> generationsOf(long teamId) {
		Map<Long, Integer> generations = new HashMap<>();
		teamMemberRepository.findAllByTeamId(teamId)
			.forEach(member -> generations.put(member.getAccountId(), member.getGeneration()));
		return generations;
	}

	/** 그 팀원의 지금 기수. 기수가 없거나 팀원이 아니면 null. */
	public Integer generationOf(long teamId, long accountId) {
		return teamMemberRepository.findByTeamIdAndAccountId(teamId, accountId)
			.map(TeamMember::getGeneration)
			.orElse(null);
	}

	/** 팀장의 계정 ID. 팀에는 항상 팀장이 한 명 있다. */
	public long leaderIdOf(long teamId) {
		return teamMemberRepository.findByTeamIdAndRole(teamId, TeamRole.LEADER)
			.orElseThrow(() -> new IllegalStateException("팀장이 없다: " + teamId))
			.getAccountId();
	}

}
