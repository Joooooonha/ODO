package com.odo.schedule.presentation;

import java.util.List;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.schedule.application.ScheduleParticipantService;
import com.odo.schedule.presentation.dto.ChangeParticipantStatusRequest;
import com.odo.schedule.presentation.dto.ParticipantResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 참여자 목록과 참여자 상태 변경. 03_Schedule_spec.md의 "API 명세".
 * 상태 변경은 참여자의 여러 속성 중 상태 하나만 바꾸므로 PATCH다.
 */
@RestController
@RequestMapping("/api/schedules/{scheduleId}/participants")
@RequiredArgsConstructor
public class ScheduleParticipantController {

	private final ScheduleParticipantService participantService;

	@GetMapping
	public List<ParticipantResponse> findParticipants(@LoginAccountId long accountId, @PathVariable long scheduleId) {
		return participantService.findParticipants(accountId, scheduleId).stream().map(ParticipantResponse::from).toList();
	}

	@PatchMapping("/{targetAccountId}")
	public ParticipantResponse changeStatus(@LoginAccountId long accountId, @PathVariable long scheduleId,
			@PathVariable long targetAccountId, @RequestBody ChangeParticipantStatusRequest request) {
		return ParticipantResponse
			.from(participantService.changeStatus(accountId, scheduleId, targetAccountId, request.requireStatus()));
	}

}
