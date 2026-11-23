package com.odo.performance.presentation;

import java.util.List;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.performance.application.PerformanceMemberService;
import com.odo.performance.presentation.dto.AddPerformanceMembersRequest;
import com.odo.performance.presentation.dto.ChangePerformanceMemberRoleRequest;
import com.odo.performance.presentation.dto.MemberEntryRequest;
import com.odo.performance.presentation.dto.PerformanceMemberResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 명단 조회, 명단에 추가, 디렉터 지정. 06_Performance_spec.md의 "API 명세".
 * 명단의 한 사람은 그 공연 안에서만 accountId로 정해지므로 공연 아래에 둔다.
 */
@RestController
@RequestMapping("/api/performances/{performanceId}/members")
@RequiredArgsConstructor
public class PerformanceMemberController {

	private final PerformanceMemberService performanceMemberService;

	@GetMapping
	public List<PerformanceMemberResponse> findPerformanceMembers(@LoginAccountId long accountId,
			@PathVariable long performanceId) {
		return performanceMemberService.findPerformanceMembers(accountId, performanceId)
			.stream()
			.map(PerformanceMemberResponse::from)
			.toList();
	}

	/** 여러 명을 한 번에 넣으므로 자원 하나의 주소(Location)는 주지 않는다. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public List<PerformanceMemberResponse> addPerformanceMembers(@LoginAccountId long accountId,
			@PathVariable long performanceId, @RequestBody AddPerformanceMembersRequest request) {
		return performanceMemberService
			.addPerformanceMembers(accountId, performanceId, MemberEntryRequest.toAssignments(request.members()))
			.stream()
			.map(PerformanceMemberResponse::from)
			.toList();
	}

	@PatchMapping("/{targetAccountId}")
	public PerformanceMemberResponse changePerformanceMemberRole(@LoginAccountId long accountId, @PathVariable long performanceId,
			@PathVariable long targetAccountId, @RequestBody ChangePerformanceMemberRoleRequest request) {
		return PerformanceMemberResponse.from(
				performanceMemberService.changePerformanceMemberRole(accountId, performanceId, targetAccountId, request.role()));
	}

}
