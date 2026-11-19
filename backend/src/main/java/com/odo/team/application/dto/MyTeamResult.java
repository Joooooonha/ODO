package com.odo.team.application.dto;

import com.odo.team.domain.entity.TeamRole;

/** 내 팀 목록의 한 항목. */
public record MyTeamResult(long id, String name, TeamRole myRole) {

}
