package com.odo.team.presentation;

import java.util.List;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.team.application.JoinRequestService;
import com.odo.team.presentation.dto.JoinRequestResponse;
import com.odo.team.presentation.dto.PendingJoinRequestResponse;
import com.odo.team.presentation.dto.TeamMemberResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 입장 요청 목록과 취소, 팀장의 대기 목록과 승인, 거절.
 */
@RestController
@RequiredArgsConstructor
public class JoinRequestController {

	private final JoinRequestService joinRequestService;

	@GetMapping("/api/join-requests/me")
	public List<JoinRequestResponse> myRequests(@LoginAccountId long accountId) {
		return joinRequestService.myRequests(accountId).stream().map(JoinRequestResponse::from).toList();
	}

	@DeleteMapping("/api/join-requests/{requestId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cancel(@LoginAccountId long accountId, @PathVariable long requestId) {
		joinRequestService.cancel(accountId, requestId);
	}

	@GetMapping("/api/teams/{teamId}/join-requests")
	public List<PendingJoinRequestResponse> pendingRequests(@LoginAccountId long accountId,
			@PathVariable long teamId) {
		return joinRequestService.pendingRequests(accountId, teamId)
			.stream()
			.map(PendingJoinRequestResponse::from)
			.toList();
	}

	@PostMapping("/api/teams/{teamId}/join-requests/{requestId}/approve")
	public TeamMemberResponse approve(@LoginAccountId long accountId, @PathVariable long teamId,
			@PathVariable long requestId) {
		return TeamMemberResponse.from(joinRequestService.approve(accountId, teamId, requestId));
	}

	@PostMapping("/api/teams/{teamId}/join-requests/{requestId}/reject")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reject(@LoginAccountId long accountId, @PathVariable long teamId, @PathVariable long requestId) {
		joinRequestService.reject(accountId, teamId, requestId);
	}

}
