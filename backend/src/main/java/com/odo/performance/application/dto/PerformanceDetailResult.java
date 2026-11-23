package com.odo.performance.application.dto;

import com.odo.performance.domain.entity.Performance;
import com.odo.performance.domain.entity.PerformanceRole;

/**
 * 공연 상세. 공연 상세 조회, 공연 만들기와 수정 직후의 응답에 쓴다.
 * URL(/api/performances/{id})에 팀이 없으므로 teamId를 준다. myRole은 보는 사람의 공연 안 역할이다.
 */
public record PerformanceDetailResult(
		long id,
		long teamId,
		String name,
		String description,
		PerformanceRole myRole) {

	public static PerformanceDetailResult of(Performance performance, PerformanceRole myRole) {
		return new PerformanceDetailResult(performance.getId(), performance.getTeamId(), performance.getName(),
				performance.getDescription(), myRole);
	}

}
