package com.odo.team.presentation;

import java.util.List;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.team.application.TeamService;
import com.odo.team.presentation.dto.ChangeGenerationRequest;
import com.odo.team.presentation.dto.CreateTeamRequest;
import com.odo.team.presentation.dto.InviteCodeResponse;
import com.odo.team.presentation.dto.MyTeamResponse;
import com.odo.team.presentation.dto.TeamDetailResponse;
import com.odo.team.presentation.dto.TeamMemberResponse;
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
 * 팀 스페이스 만들기, 내 팀 목록, 팀 상세, 팀원 목록, 기수 변경, 초대 코드. 02_TeamSpace_spec.md의 "API 명세".
 */
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamController {

	private final TeamService teamService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TeamDetailResponse createTeam(@LoginAccountId long accountId, @RequestBody CreateTeamRequest request) {
		return TeamDetailResponse
			.from(teamService.createTeam(accountId, request.name(), request.description(), request.generation()));
	}

	@GetMapping
	public List<MyTeamResponse> findMyTeams(@LoginAccountId long accountId) {
		return teamService.findMyTeams(accountId).stream().map(MyTeamResponse::from).toList();
	}

	@GetMapping("/{teamId}")
	public TeamDetailResponse findTeamDetail(@LoginAccountId long accountId, @PathVariable long teamId) {
		return TeamDetailResponse.from(teamService.findTeamDetail(accountId, teamId));
	}

	@GetMapping("/{teamId}/members")
	public List<TeamMemberResponse> findTeamMembers(@LoginAccountId long accountId, @PathVariable long teamId) {
		return teamService.findTeamMembers(accountId, teamId).stream().map(TeamMemberResponse::from).toList();
	}

	@PatchMapping("/{teamId}/members/me")
	public TeamMemberResponse changeMyGeneration(@LoginAccountId long accountId, @PathVariable long teamId,
			@RequestBody ChangeGenerationRequest request) {
		return TeamMemberResponse.from(teamService.changeMyGeneration(accountId, teamId, request.generation()));
	}

	@GetMapping("/{teamId}/invite-code")
	public InviteCodeResponse findInviteCode(@LoginAccountId long accountId, @PathVariable long teamId) {
		return new InviteCodeResponse(teamService.findInviteCode(accountId, teamId));
	}

	@PostMapping("/{teamId}/invite-code")
	public InviteCodeResponse regenerateInviteCode(@LoginAccountId long accountId, @PathVariable long teamId) {
		return new InviteCodeResponse(teamService.regenerateInviteCode(accountId, teamId));
	}

}
