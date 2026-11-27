package com.odo.performance.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.odo.performance.application.dto.MemberAssignment;
import com.odo.performance.application.dto.MyPerformanceResult;
import com.odo.performance.application.dto.PerformanceDetailResult;
import com.odo.performance.domain.entity.Performance;
import com.odo.performance.domain.entity.PerformanceMember;
import com.odo.performance.domain.exception.LeaderNotInMembersException;
import com.odo.performance.domain.exception.PerformanceNotFoundException;
import com.odo.performance.domain.repository.PerformanceMemberRepository;
import com.odo.performance.domain.repository.PerformanceRepository;
import com.odo.team.application.TeamAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공연 만들기, 공연 목록, 공연 상세, 공연 수정. 06_Performance_spec.md의 "API 명세".
 * 공연을 만들면 같은 트랜잭션 안에서 명단 추가 이벤트를 발행한다(06_Performance_spec.md의 "알림 이벤트").
 */
@Service
@RequiredArgsConstructor
public class PerformanceService {

	private final PerformanceRepository performanceRepository;

	private final PerformanceMemberRepository performanceMemberRepository;

	private final PerformanceRoster performanceRoster;

	private final PerformanceAccess performanceAccess;

	private final TeamAccess teamAccess;

	private final ApplicationEventPublisher eventPublisher;

	private final Clock clock;

	/**
	 * 공연과 명단을 한 트랜잭션으로 만든다. 오류가 하나라도 있으면 아무것도 남지 않는다.
	 * 팀원 아님 TeamNotFoundException, 팀장 아님 NotTeamLeaderException, 이름과 설명 규칙 위반,
	 * 명단에 팀장이 없음 LeaderNotInMembersException, 그 밖의 명단 오류는 PerformanceRoster.enrollMembers와 같다.
	 */
	@Transactional
	public PerformanceDetailResult createPerformance(long leaderId, long teamId, String name, String description,
			List<MemberAssignment> members) {
		teamAccess.requireLeader(teamId, leaderId);
		Performance performance = Performance.createPerformance(teamId, name, description, clock.instant());
		MemberAssignment leader = members.stream()
			.filter(member -> member.accountId() == leaderId)
			.findFirst()
			.orElseThrow(LeaderNotInMembersException::new);
		performanceRepository.save(performance);
		performanceRoster.enrollMembers(performance.getId(), teamId, members, performance.getCreatedAt());
		eventPublisher.publishEvent(PerformanceRoster.enrolledEvent(teamId, performance.getId(), leaderId, members));
		return PerformanceDetailResult.of(performance, leader.role());
	}

	/** 그 팀에서 내가 명단에 있는 공연만, 최근에 만든 것부터. 팀장은 모든 공연의 명단에 있다. 팀원 아님 TeamNotFoundException. */
	@Transactional(readOnly = true)
	public List<MyPerformanceResult> findMyPerformances(long accountId, long teamId) {
		teamAccess.requireMember(teamId, accountId);
		Map<Long, PerformanceMember> myRows = performanceMemberRepository.findAllByTeamIdAndAccountId(teamId, accountId)
			.stream()
			.collect(Collectors.toMap(PerformanceMember::getPerformanceId, Function.identity()));
		if (myRows.isEmpty()) {
			return List.of();
		}
		return performanceRepository.findAllByIdInOrderByCreatedAtDescIdDesc(myRows.keySet())
			.stream()
			.map(performance -> MyPerformanceResult.of(performance, myRows.get(performance.getId()).getRole()))
			.toList();
	}

	/** 명단에 없음 PerformanceNotFoundException. */
	@Transactional(readOnly = true)
	public PerformanceDetailResult findPerformanceDetail(long accountId, long performanceId) {
		PerformanceMember me = performanceAccess.requireParticipant(performanceId, accountId);
		return PerformanceDetailResult.of(findPerformance(performanceId), me.getRole());
	}

	/** 이름과 설명을 모두 바꾼다. 명단에 없음 PerformanceNotFoundException, 팀장 아님 NotTeamLeaderException, 규칙 위반. */
	@Transactional
	public PerformanceDetailResult updatePerformance(long accountId, long performanceId, String name,
			String description) {
		PerformanceMember me = performanceAccess.requireLeader(performanceId, accountId);
		Performance performance = findPerformance(performanceId);
		performance.updatePerformance(name, description, clock.instant());
		return PerformanceDetailResult.of(performance, me.getRole());
	}

	private Performance findPerformance(long performanceId) {
		// 명단 행이 공연을 참조하므로(FK) 명단에 있으면 공연도 있다
		return performanceRepository.findById(performanceId).orElseThrow(PerformanceNotFoundException::new);
	}

}
