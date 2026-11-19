package com.odo.team.domain.repository;

import java.util.List;
import java.util.Optional;

import com.odo.team.domain.entity.Team;

public interface TeamRepository {

	Team save(Team team);

	Optional<Team> findById(Long id);

	List<Team> findAllById(Iterable<Long> ids);

	/** 무효가 된 이전 코드는 어느 팀에도 없으므로 찾지 못한다. */
	Optional<Team> findByInviteCode(String inviteCode);

}
