package com.odo.performance.presentation.dto;

import com.odo.performance.application.dto.MyPerformanceResult;
import com.odo.performance.domain.entity.PerformanceRole;

public record MyPerformanceResponse(long id, String name, String description, PerformanceRole myRole) {

	public static MyPerformanceResponse from(MyPerformanceResult result) {
		return new MyPerformanceResponse(result.id(), result.name(), result.description(), result.myRole());
	}

}
