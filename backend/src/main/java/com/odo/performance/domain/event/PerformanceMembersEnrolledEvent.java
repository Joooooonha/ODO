package com.odo.performance.domain.event;

import java.util.List;

import com.odo.performance.domain.entity.PerformanceRole;

/**
 * 공연 명단에 사람이 들어갔다(공연 만들기, 명단에 추가). 같은 트랜잭션 안에서 동기로 발행한다.
 * enrollments는 넣은 사람과 넣을 때의 역할이다. 06_Performance_spec.md의 "알림 이벤트".
 */
public record PerformanceMembersEnrolledEvent(
		long teamId,
		long performanceId,
		long actorAccountId,
		List<Enrollment> enrollments) {

	public record Enrollment(
			long accountId,
			PerformanceRole role) {

	}

}
