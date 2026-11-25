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
	 * 팀 행을 FOR NO KEY UPDATE로 잠그고 트랜잭션이 끝날 때까지 잡는다. 팀이 없으면 null이다.
	 * 팀원 승인과 일정 만들기가 같은 팀에서 한 번에 하나씩 돌게 해, 새 팀원이 새 일정에서 빠지지 않게 한다.
	 * FOR UPDATE가 아닌 이유: 이 팀을 FK로 가리키는 행의 INSERT는 FK 확인으로 팀 행에 FOR KEY SHARE를 건다.
	 * FOR UPDATE는 이것과 충돌해서, 일정 행을 잠근 뒤 알림을 기록하는 트랜잭션(일정 수정, 삭제)과 승인이 서로를 기다리는 교착이 생긴다.
	 * FOR NO KEY UPDATE는 FK 확인과 충돌하지 않고, 같은 잠금끼리는 충돌하므로 한 번에 하나씩 돌게 하는 목적은 그대로다.
	 */
	Long lockById(long id);

}
