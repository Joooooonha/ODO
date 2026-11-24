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

	/**
	 * 팀 행을 FOR UPDATE로 잠그고 트랜잭션이 끝날 때까지 잡는다. 팀이 없으면 null이다.
	 * 팀원 승인과 일정 만들기가 같은 팀에서 한 번에 하나씩 돌게 해, 새 팀원이 새 일정에서 빠지지 않게 한다.
	 */
	Long lockById(long id);

}
