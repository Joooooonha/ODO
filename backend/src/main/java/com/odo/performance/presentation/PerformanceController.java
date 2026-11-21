package com.odo.performance.presentation;

import java.net.URI;
import java.util.List;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.performance.application.PerformanceService;
import com.odo.performance.presentation.dto.CreatePerformanceRequest;
import com.odo.performance.presentation.dto.MemberEntryRequest;
import com.odo.performance.presentation.dto.MyPerformanceResponse;
import com.odo.performance.presentation.dto.PerformanceDetailResponse;
import com.odo.performance.presentation.dto.UpdatePerformanceRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공연 만들기, 공연 목록, 공연 상세, 공연 수정. 06_Performance_spec.md의 "API 명세".
 * 만들기와 목록은 팀이 있어야 범위가 정해지므로 팀 아래에, 공연 하나는 자기 ID로 둔다(얕은 중첩).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PerformanceController {

	private final PerformanceService performanceService;

	/** 201과 새 공연의 주소(Location)를 준다. */
	@PostMapping("/teams/{teamId}/performances")
	public ResponseEntity<PerformanceDetailResponse> createPerformance(@LoginAccountId long accountId,
			@PathVariable long teamId, @RequestBody CreatePerformanceRequest request) {
		PerformanceDetailResponse response = PerformanceDetailResponse
			.from(performanceService.createPerformance(accountId, teamId, request.name(), request.description(),
					MemberEntryRequest.toAssignments(request.members())));
		return ResponseEntity.created(URI.create("/api/performances/" + response.id())).body(response);
	}

	@GetMapping("/teams/{teamId}/performances")
	public List<MyPerformanceResponse> findMyPerformances(@LoginAccountId long accountId, @PathVariable long teamId) {
		return performanceService.findMyPerformances(accountId, teamId).stream().map(MyPerformanceResponse::from).toList();
	}

	@GetMapping("/performances/{performanceId}")
	public PerformanceDetailResponse findPerformanceDetail(@LoginAccountId long accountId,
			@PathVariable long performanceId) {
		return PerformanceDetailResponse.from(performanceService.findPerformanceDetail(accountId, performanceId));
	}

	@PutMapping("/performances/{performanceId}")
	public PerformanceDetailResponse updatePerformance(@LoginAccountId long accountId, @PathVariable long performanceId,
			@RequestBody UpdatePerformanceRequest request) {
		return PerformanceDetailResponse.from(
				performanceService.updatePerformance(accountId, performanceId, request.name(), request.description()));
	}

}
