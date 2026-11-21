package com.odo.performance.presentation.dto;

import com.odo.performance.application.dto.PerformanceDetailResult;
import com.odo.performance.domain.entity.PerformanceRole;

public record PerformanceDetailResponse(long id, long teamId, String name, String description,
		PerformanceRole myRole) {

	public static PerformanceDetailResponse from(PerformanceDetailResult result) {
		return new PerformanceDetailResponse(result.id(), result.teamId(), result.name(), result.description(),
				result.myRole());
	}

}
