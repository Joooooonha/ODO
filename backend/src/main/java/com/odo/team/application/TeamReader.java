package com.odo.team.application;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import com.odo.team.domain.entity.Team;
import com.odo.team.domain.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 다른 기능이 응답에 넣을 팀 값을 읽는다. 알림이 팀 이름을 볼 때 읽는 데 쓴다(11_Notification_spec.md의 "보여 줄 값").
 * 호출하는 쪽의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class TeamReader {

	private final TeamRepository teamRepository;

	/** 여러 팀의 이름을 한 번의 쿼리(IN)로 읽는다. 없는 팀은 결과에 없다. */
	public Map<Long, String> namesOf(Collection<Long> teamIds) {
		return teamRepository.findAllById(teamIds).stream().collect(Collectors.toMap(Team::getId, Team::getName));
	}

}
