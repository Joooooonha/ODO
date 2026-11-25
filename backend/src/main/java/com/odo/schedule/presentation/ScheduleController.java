package com.odo.schedule.presentation;

import java.net.URI;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.common.error.InvalidRequestException;
import com.odo.schedule.application.ScheduleService;
import com.odo.schedule.application.dto.ScheduleListStatus;
import com.odo.schedule.presentation.dto.CreateScheduleRequest;
import com.odo.schedule.presentation.dto.ScheduleDetailResponse;
import com.odo.schedule.presentation.dto.ScheduleListResponse;
import com.odo.schedule.presentation.dto.UpdateScheduleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 일정 만들기, 목록, 상세, 수정, 삭제. 03_Schedule_spec.md의 "API 명세".
 * 만들기와 목록은 팀 아래에, 일정 하나는 자기 ID로 둔다(얕은 중첩).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScheduleController {

	private static final int MAX_PAGE_SIZE = 50;

	private final ScheduleService scheduleService;

	/** 201과 새 일정의 주소(Location)를 준다. */
	@PostMapping("/teams/{teamId}/schedules")
	public ResponseEntity<ScheduleDetailResponse> createSchedule(@LoginAccountId long accountId,
			@PathVariable long teamId, @RequestBody CreateScheduleRequest request) {
		ScheduleDetailResponse response = ScheduleDetailResponse
			.from(scheduleService.createSchedule(accountId, teamId, request.toCommand()));
		return ResponseEntity.created(URI.create("/api/schedules/" + response.id())).body(response);
	}

	/** status는 UPCOMING(기본)이나 DONE이다. page는 0부터, size는 1~50(기본 20). 범위를 벗어나면 INVALID_REQUEST다. */
	@GetMapping("/teams/{teamId}/schedules")
	public ScheduleListResponse findSchedules(@LoginAccountId long accountId, @PathVariable long teamId,
			@RequestParam(defaultValue = "UPCOMING") ScheduleListStatus status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new InvalidRequestException();
		}
		return ScheduleListResponse.from(scheduleService.findSchedules(accountId, teamId, status, page, size));
	}

	@GetMapping("/schedules/{scheduleId}")
	public ScheduleDetailResponse findSchedule(@LoginAccountId long accountId, @PathVariable long scheduleId) {
		return ScheduleDetailResponse.from(scheduleService.findSchedule(accountId, scheduleId));
	}

	@PutMapping("/schedules/{scheduleId}")
	public ScheduleDetailResponse updateSchedule(@LoginAccountId long accountId, @PathVariable long scheduleId,
			@RequestBody UpdateScheduleRequest request) {
		return ScheduleDetailResponse.from(scheduleService.updateSchedule(accountId, scheduleId, request.toCommand()));
	}

	/** 성공하면 돌려줄 본문이 없어 204다. */
	@DeleteMapping("/schedules/{scheduleId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteSchedule(@LoginAccountId long accountId, @PathVariable long scheduleId) {
		scheduleService.deleteSchedule(accountId, scheduleId);
	}

}
