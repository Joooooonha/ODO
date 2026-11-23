package com.odo.performance.presentation.dto;

import com.odo.performance.application.dto.PerformanceMemberResult;
import com.odo.performance.domain.entity.PerformanceRole;

public record PerformanceMemberResponse(
		long accountId,
		String name,
		Integer generation,
		PerformanceRole role) {

	public static PerformanceMemberResponse from(PerformanceMemberResult result) {
		return new PerformanceMemberResponse(result.accountId(), result.name(), result.generation(), result.role());
	}

}
