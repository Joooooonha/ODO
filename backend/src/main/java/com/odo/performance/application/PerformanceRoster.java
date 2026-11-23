package com.odo.performance.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.odo.performance.application.dto.MemberAssignment;
import com.odo.performance.application.dto.PerformanceMemberResult;
import com.odo.performance.domain.entity.PerformanceMember;
import com.odo.performance.domain.exception.AlreadyInPerformanceException;
import com.odo.performance.domain.exception.DuplicateMemberException;
import com.odo.performance.domain.exception.NotTeamMemberException;
import com.odo.performance.domain.repository.PerformanceMemberRepository;
import com.odo.team.domain.entity.TeamMember;
import com.odo.team.domain.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 명단에 사람을 넣는다. 공연 만들기와 명단에 추가가 같이 쓴다. 호출하는 쪽의 트랜잭션 안에서 돈다.
 * 하나라도 오류가 있으면 예외를 던지고, 호출한 트랜잭션이 롤백되어 아무도 들어가지 않는다.
 */
@Component
@RequiredArgsConstructor
class PerformanceRoster {

	/** 한 사람이 한 공연 명단에 한 번만 들어가게 하는 유니크 제약. V3 마이그레이션. */
	private static final String MEMBER_UNIQUE_CONSTRAINT = "uk_performance_member_performance_account";

	private final PerformanceMemberRepository performanceMemberRepository;

	private final TeamMemberRepository teamMemberRepository;

	private final AccountNames accountNames;

	/**
	 * 요청 순서대로 넣고, 넣은 항목을 같은 순서로 돌려준다.
	 * 같은 사람 두 번 DuplicateMemberException, 그 팀의 팀원이 아님 NotTeamMemberException,
	 * 이미 명단에 있음 AlreadyInPerformanceException.
	 */
	List<PerformanceMemberResult> enrollMembers(long performanceId, long teamId, List<MemberAssignment> assignments,
			Instant now) {
		List<Long> accountIds = assignments.stream().map(MemberAssignment::accountId).toList();
		Set<Long> distinct = new HashSet<>(accountIds);
		if (distinct.size() != accountIds.size()) {
			throw new DuplicateMemberException();
		}
		Map<Long, TeamMember> teamMembers = teamMemberRepository.findAllByTeamId(teamId)
			.stream()
			.collect(Collectors.toMap(TeamMember::getAccountId, Function.identity()));
		if (!teamMembers.keySet().containsAll(distinct)) {
			throw new NotTeamMemberException();
		}
		if (!performanceMemberRepository.findAllByPerformanceIdAndAccountIdIn(performanceId, distinct).isEmpty()) {
			throw new AlreadyInPerformanceException();
		}
		List<PerformanceMember> members = assignments.stream()
			.map(assignment -> PerformanceMember.createPerformanceMember(performanceId, teamId, assignment.accountId(),
					assignment.role(), now))
			.toList();
		// 같은 사람을 동시에 추가하면 둘 다 위 확인을 통과할 수 있다.
		// 그때는 DB의 유니크 제약이 하나만 남긴다. 그 위반을 같은 409로 바꾼다.
		try {
			performanceMemberRepository.saveAllAndFlush(members);
		}
		catch (DataIntegrityViolationException ex) {
			if (violates(ex, MEMBER_UNIQUE_CONSTRAINT)) {
				throw new AlreadyInPerformanceException();
			}
			throw ex;
		}
		Map<Long, String> names = accountNames.namesOf(distinct);
		return members.stream()
			.map(member -> PerformanceMemberResult.of(member, names.get(member.getAccountId()),
					teamMembers.get(member.getAccountId()).getGeneration()))
			.toList();
	}

	/** 예외 원인을 따라가며 위반된 제약 이름을 찾는다. */
	private static boolean violates(DataIntegrityViolationException ex, String constraintName) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return constraintName.equalsIgnoreCase(violation.getConstraintName());
			}
		}
		return false;
	}

}
