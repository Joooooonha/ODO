package com.odo.schedule.presentation.dto;

import java.util.List;

import com.odo.schedule.application.dto.ScheduleListResult;

public record ScheduleListResponse(
		List<ScheduleSummaryResponse> items,
		int page,
		int size,
		long totalCount) {

	public static ScheduleListResponse from(ScheduleListResult result) {
		return new ScheduleListResponse(result.items().stream().map(ScheduleSummaryResponse::from).toList(),
				result.page(), result.size(), result.totalCount());
	}

}
