package com.odo.team.application.dto;

import com.odo.team.domain.TeamMember;
import com.odo.team.domain.TeamRole;

/** 팀원 목록의 한 항목. 기수 변경과 승인의 결과도 이 형식이다. */
public record TeamMemberResult(long accountId, String name, Integer generation, TeamRole role) {

	public static TeamMemberResult of(TeamMember member, String name) {
		return new TeamMemberResult(member.getAccountId(), name, member.getGeneration(), member.getRole());
	}

}
