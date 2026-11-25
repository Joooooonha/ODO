package com.odo.schedule.presentation;

import java.net.URI;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.common.server.ServerName;
import com.odo.schedule.application.VoteIntakeService;
import com.odo.schedule.application.VoteResultService;
import com.odo.schedule.application.dto.VoteRequestResult;
import com.odo.schedule.presentation.dto.CastVoteRequest;
import com.odo.schedule.presentation.dto.VoteAcceptedResponse;
import com.odo.schedule.presentation.dto.VoteResultResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 참여 투표와 결과 조회. 03_Schedule_spec.md의 "투표", "투표 결과".
 * 투표는 바로 확정하지 않으므로 접수했다는 202와 결과를 물어볼 주소(Location)를 돌려준다.
 */
@RestController
@RequiredArgsConstructor
public class VoteController {

	private final VoteIntakeService voteIntakeService;

	private final VoteResultService voteResultService;

	private final ServerName serverName;

	/** 로그인과 본문 형식만 확인한다. 팀원인지, 마감과 정원은 작업자가 처리할 때 확인하고 결과 조회로 알려 준다. */
	@PostMapping("/api/schedules/{scheduleId}/votes")
	public ResponseEntity<VoteAcceptedResponse> vote(@LoginAccountId long accountId, @PathVariable long scheduleId,
			@RequestBody CastVoteRequest request, HttpServletRequest httpRequest) {
		VoteRequestResult result = voteIntakeService.accept(scheduleId, accountId, request.requireChoice(),
				VoteArrivalFilter.arrivedAt(httpRequest), serverName.value());
		return ResponseEntity.accepted()
			.location(URI.create("/api/vote-requests/" + result.requestId()))
			.body(VoteAcceptedResponse.from(result));
	}

	@GetMapping("/api/vote-requests/{requestId}")
	public VoteResultResponse findResult(@LoginAccountId long accountId, @PathVariable long requestId) {
		return VoteResultResponse.from(voteResultService.findResult(accountId, requestId));
	}

}
