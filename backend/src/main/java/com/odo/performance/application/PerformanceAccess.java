package com.odo.performance.application;

import com.odo.performance.domain.entity.PerformanceMember;
import com.odo.performance.domain.exception.NotDirectorException;
import com.odo.performance.domain.exception.PerformanceNotFoundException;
import com.odo.performance.domain.repository.PerformanceMemberRepository;
import com.odo.team.application.TeamAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공연 명단에 있는지, 디렉터인지, 팀장인지 확인한다. 06_Performance_spec.md의 "역할과 권한 확인".
 * 요청마다 DB에서 확인한다. 명단 행은 팀원 행을 참조하므로(복합 FK) 명단에 있으면 그 팀의 팀원이기도 하다.
 * 호출하는 쪽의 트랜잭션 안에서 돌고, 돌려준 명단 행은 그 트랜잭션의 영속 상태다.
 */
@Component
@RequiredArgsConstructor
public class PerformanceAccess {

	private final PerformanceMemberRepository performanceMemberRepository;

	private final TeamAccess teamAccess;

	/**
	 * 명단에 있으면 그 명단 행을 돌려준다.
	 * 명단에 없거나 공연이 없으면 PerformanceNotFoundException(404). 같은 팀원에게도 공연이 있다는 것을 숨긴다.
	 */
	public PerformanceMember requireParticipant(long performanceId, long accountId) {
		return performanceMemberRepository.findByPerformanceIdAndAccountId(performanceId, accountId)
			.orElseThrow(PerformanceNotFoundException::new);
	}

	/** 디렉터면 그 명단 행을 돌려준다. 명단에 없으면 PerformanceNotFoundException, 디렉터가 아니면 NotDirectorException(403). */
	public PerformanceMember requireDirector(long performanceId, long accountId) {
		PerformanceMember participant = requireParticipant(performanceId, accountId);
		if (!participant.isDirector()) {
			throw new NotDirectorException();
		}
		return participant;
	}

	/**
	 * 명단에 있고 팀장이면 그 명단 행을 돌려준다.
	 * 명단에 없으면 PerformanceNotFoundException, 팀장이 아니면 NotTeamLeaderException(403).
	 * 팀장인지는 명단이 아니라 팀원의 역할로 본다.
	 */
	public PerformanceMember requireLeader(long performanceId, long accountId) {
		PerformanceMember participant = requireParticipant(performanceId, accountId);
		teamAccess.requireLeader(participant.getTeamId(), accountId);
		return participant;
	}

}
