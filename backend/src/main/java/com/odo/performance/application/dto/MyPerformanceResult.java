package com.odo.performance.application.dto;

import com.odo.performance.domain.entity.Performance;
import com.odo.performance.domain.entity.PerformanceRole;

/** 공연 목록의 한 항목. 팀 안의 목록이라 teamId는 주지 않는다. */
public record MyPerformanceResult(long id, String name, String description, PerformanceRole myRole) {

	public static MyPerformanceResult of(Performance performance, PerformanceRole myRole) {
		return new MyPerformanceResult(performance.getId(), performance.getName(), performance.getDescription(), myRole);
	}

}
