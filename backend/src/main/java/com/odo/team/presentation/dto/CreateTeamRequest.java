package com.odo.team.presentation.dto;

/** description과 generation은 선택이다. generation은 만드는 사람(팀장)의 기수다. */
public record CreateTeamRequest(String name, String description, Integer generation) {

}
