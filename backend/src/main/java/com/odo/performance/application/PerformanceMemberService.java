package com.odo.performance.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.odo.account.application.AccountReader;
import com.odo.performance.application.dto.MemberAssignment;
import com.odo.performance.application.dto.PerformanceMemberResult;
import com.odo.performance.domain.entity.PerformanceMember;
import com.odo.performance.domain.entity.PerformanceRole;
import com.odo.performance.domain.exception.EmptyMembersException;
import com.odo.performance.domain.exception.InvalidRoleException;
import com.odo.performance.domain.exception.PerformanceMemberNotFoundException;
import com.odo.performance.domain.repository.PerformanceMemberRepository;
import com.odo.team.application.TeamMemberReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 명단 조회, 명단에 추가, 디렉터 지정. 06_Performance_spec.md의 "API 명세".
 */
@Service
@RequiredArgsConstructor
public class PerformanceMemberService {

	/** 디렉터를 먼저, 같은 역할 안에서는 명단에 들어온 순서. 한 요청으로 같이 들어온 사람은 요청 순서(id). */
	private static final Comparator<PerformanceMember> ROSTER_ORDER = Comparator
		.comparing((PerformanceMember member) -> !member.isDirector())
		.thenComparing(PerformanceMember::getJoinedAt)
		.thenComparing(PerformanceMember::getId);

	private final PerformanceMemberRepository performanceMemberRepository;

	private final TeamMemberReader teamMemberReader;

	private final PerformanceRoster performanceRoster;

	private final PerformanceAccess performanceAccess;

	private final AccountReader accountReader;

	private final Clock clock;

	/** 명단에 없음 PerformanceNotFoundException. */
	@Transactional(readOnly = true)
	public List<PerformanceMemberResult> findPerformanceMembers(long accountId, long performanceId) {
		PerformanceMember me = performanceAccess.requireParticipant(performanceId, accountId);
		List<PerformanceMember> members = performanceMemberRepository.findAllByPerformanceId(performanceId)
			.stream()
			.sorted(ROSTER_ORDER)
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
		return performanceRoster.enrollMembers(performanceId, leader.getTeamId(), members, clock.instant());
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
		PerformanceMember target = performanceMemberRepository
			.findByPerformanceIdAndAccountId(performanceId, targetAccountId)
			.orElseThrow(PerformanceMemberNotFoundException::new);
		target.designateDirector(clock.instant());
		Integer generation = teamMemberReader.generationOf(leader.getTeamId(), targetAccountId);
		return PerformanceMemberResult.of(target, accountReader.nameOf(targetAccountId), generation);
	}

}
