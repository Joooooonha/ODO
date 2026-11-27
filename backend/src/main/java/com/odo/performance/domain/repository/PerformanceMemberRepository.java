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

	/**
	 * 명단의 한 사람 행을 FOR UPDATE로 잠그고 읽는다. 디렉터 지정이 쓴다.
	 * 같은 사람을 동시에 지정하면 뒤의 것이 앞의 커밋을 기다린 뒤 바뀐 역할을 읽어, 지정 이벤트가 한 번만 나간다(PR #16 리뷰).
	 * 이 행을 FK로 가리키는 테이블이 없어 다른 INSERT를 막지 않는다.
	 */
	Optional<PerformanceMember> lockByPerformanceIdAndAccountId(long performanceId, long accountId);

	List<PerformanceMember> findAllByPerformanceId(Long performanceId);

	/** 명단에 넣으려는 사람 중 이미 있는 사람. */
	List<PerformanceMember> findAllByPerformanceIdAndAccountIdIn(Long performanceId, Collection<Long> accountIds);

	/** 그 팀에서 내가 명단에 있는 행. index (team_id, account_id)로 찾는다. */
	List<PerformanceMember> findAllByTeamIdAndAccountId(Long teamId, Long accountId);

}
