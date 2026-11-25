package com.odo.schedule.application.dto;

import java.util.List;

/** 일정 목록 한 페이지. totalCount는 그 구분(예정이나 완료)의 전체 일정 수다. */
public record ScheduleListResult(
		List<ScheduleSummaryResult> items,
		int page,
		int size,
		long totalCount) {

}
