package com.odo.performance.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.odo.performance.domain.entity.PerformanceMember;

public interface PerformanceMemberRepository {

	/**
	 * 명단 행을 저장하고 바로 DB에 반영한다.
	 * 같은 사람이 이미 명단에 있으면 unique (performance_id, account_id) 위반이 여기서 바로 난다.
	 */
	<S extends PerformanceMember> List<S> saveAllAndFlush(Iterable<S> members);

	/** 요청마다 하는 명단 확인. unique (performance_id, account_id) 인덱스로 찾는다. */
	Optional<PerformanceMember> findByPerformanceIdAndAccountId(Long performanceId, Long accountId);

	List<PerformanceMember> findAllByPerformanceId(Long performanceId);

	/** 명단에 넣으려는 사람 중 이미 있는 사람. */
	List<PerformanceMember> findAllByPerformanceIdAndAccountIdIn(Long performanceId, Collection<Long> accountIds);

	/** 그 팀에서 내가 명단에 있는 행. index (team_id, account_id)로 찾는다. */
	List<PerformanceMember> findAllByTeamIdAndAccountId(Long teamId, Long accountId);

}
