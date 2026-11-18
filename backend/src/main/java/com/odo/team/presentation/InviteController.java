package com.odo.team.presentation;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.team.application.JoinRequestService;
import com.odo.team.presentation.dto.CreateJoinRequestRequest;
import com.odo.team.presentation.dto.InvitePreviewResponse;
import com.odo.team.presentation.dto.JoinRequestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 초대 링크로 들어온 사람의 초대 확인과 입장 요청 보내기.
 */
@RestController
@RequestMapping("/api/invites/{inviteCode}")
@RequiredArgsConstructor
public class InviteController {

	private final JoinRequestService joinRequestService;

	@GetMapping
	public InvitePreviewResponse preview(@LoginAccountId long accountId, @PathVariable String inviteCode) {
		return InvitePreviewResponse.from(joinRequestService.preview(accountId, inviteCode));
	}

	@PostMapping("/join-requests")
	@ResponseStatus(HttpStatus.CREATED)
	public JoinRequestResponse request(@LoginAccountId long accountId, @PathVariable String inviteCode,
			@RequestBody CreateJoinRequestRequest request) {
		return JoinRequestResponse.from(joinRequestService.request(accountId, inviteCode, request.generation()));
	}

}
