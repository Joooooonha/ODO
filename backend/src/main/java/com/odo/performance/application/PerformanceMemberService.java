package com.odo.performance.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.odo.account.application.AccountReader;
import com.odo.performance.application.dto.MemberAssignment;
import com.odo.performance.application.dto.PerformanceMemberResult;
import com.odo.performance.domain.entity.PerformanceMember;
import com.odo.performance.domain.entity.PerformanceRole;
import com.odo.performance.domain.event.PerformanceDirectorDesignatedEvent;
import com.odo.performance.domain.exception.EmptyMembersException;
import com.odo.performance.domain.exception.InvalidRoleException;
import com.odo.performance.domain.exception.PerformanceMemberNotFoundException;
import com.odo.performance.domain.repository.PerformanceMemberRepository;
import com.odo.team.application.TeamMemberReader;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 명단 조회, 명단에 추가, 디렉터 지정. 06_Performance_spec.md의 "API 명세".
 * 명단에 추가하거나 실제로 디렉터로 바뀌면 같은 트랜잭션 안에서 이벤트를 발행한다(06_Performance_spec.md의 "알림 이벤트").
 */
@Service
@RequiredArgsConstructor
public class PerformanceMemberService {

	private final PerformanceMemberRepository performanceMemberRepository;

	private final TeamMemberReader teamMemberReader;

	private final PerformanceRoster performanceRoster;

	private final PerformanceAccess performanceAccess;

	private final AccountReader accountReader;

	private final ApplicationEventPublisher eventPublisher;

	private final Clock clock;

	/** 명단에 없음 PerformanceNotFoundException. */
	@Transactional(readOnly = true)
	public List<PerformanceMemberResult> findPerformanceMembers(long accountId, long performanceId) {
		PerformanceMember me = performanceAccess.requireParticipant(performanceId, accountId);
		List<PerformanceMember> members = performanceMemberRepository.findAllByPerformanceId(performanceId)
			.stream()
			.sorted(PerformanceMember.ROSTER_ORDER)
			.toList();
		Map<Long, Integer> generations = teamMemberReader.generationsOf(me.getTeamId());
		Map<Long, String> names = accountReader.namesOf(members.stream().map(PerformanceMember::getAccountId).toList());
		return members.stream()
			.map(member -> PerformanceMemberResult.of(member, names.get(member.getAccountId()),
					generations.get(member.getAccountId())))
			.toList();
	}

	/**
	 * 여러 명을 한 번에 넣는다. 하나라도 오류가 있으면 아무도 넣지 않는다.
	 * 명단에 없음 PerformanceNotFoundException, 팀장 아님 NotTeamLeaderException, 빈 목록 EmptyMembersException,
	 * 그 밖의 명단 오류는 PerformanceRoster.enrollMembers와 같다.
	 */
	@Transactional
	public List<PerformanceMemberResult> addPerformanceMembers(long leaderId, long performanceId,
			List<MemberAssignment> members) {
		PerformanceMember leader = performanceAccess.requireLeader(performanceId, leaderId);
		if (members.isEmpty()) {
			throw new EmptyMembersException();
		}
		List<PerformanceMemberResult> enrolled = performanceRoster.enrollMembers(performanceId, leader.getTeamId(),
				members, clock.instant());
		eventPublisher.publishEvent(
				PerformanceRoster.enrolledEvent(leader.getTeamId(), performanceId, leaderId, members));
		return enrolled;
	}

	/**
	 * 명단에 있는 사람의 역할을 바꾼다. role에 따라 엔티티 동작을 고른다. 1차에는 DIRECTOR(디렉터 지정)만 있다.
	 * 이미 디렉터면 그대로 둔다.
	 * 명단에 없음 PerformanceNotFoundException, 팀장 아님 NotTeamLeaderException,
	 * role이 DIRECTOR가 아님 InvalidRoleException(1차에는 해제가 없다), 그 사람이 명단에 없음 PerformanceMemberNotFoundException.
	 */
	@Transactional
	public PerformanceMemberResult changePerformanceMemberRole(long leaderId, long performanceId, long targetAccountId,
			PerformanceRole role) {
		PerformanceMember leader = performanceAccess.requireLeader(performanceId, leaderId);
		if (role != PerformanceRole.DIRECTOR) {
			throw new InvalidRoleException();
		}
		// 대상 행을 잠그고 읽는다. 동시에 같은 사람을 지정해도 뒤의 것은 바뀐 역할(DIRECTOR)을 읽어 이벤트를 다시 내지 않는다.
		// 잠금 쿼리가 이 행의 첫 읽기여야 영속성 컨텍스트에 잠근 뒤의 값이 들어간다(requireLeader는 팀장 행만 읽는다).
		PerformanceMember target = performanceMemberRepository
			.lockByPerformanceIdAndAccountId(performanceId, targetAccountId)
			.orElseThrow(PerformanceMemberNotFoundException::new);
		if (target.designateDirector(clock.instant())) {
			eventPublisher.publishEvent(new PerformanceDirectorDesignatedEvent(leader.getTeamId(), performanceId,
					leaderId, targetAccountId));
		}
		Integer generation = teamMemberReader.generationOf(leader.getTeamId(), targetAccountId);
		return PerformanceMemberResult.of(target, accountReader.nameOf(targetAccountId), generation);
	}

}
