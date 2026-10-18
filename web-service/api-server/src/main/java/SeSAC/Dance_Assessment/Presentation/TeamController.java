package SeSAC.Dance_Assessment.Presentation;

import SeSAC.Dance_Assessment.Dto.Team.JoinCodeRequest;
import SeSAC.Dance_Assessment.Dto.Team.JoinCodeResponse;
import SeSAC.Dance_Assessment.Dto.Team.JoinPreviewResponse;
import SeSAC.Dance_Assessment.Dto.Team.TeamCreateRequest;
import SeSAC.Dance_Assessment.Dto.Team.TeamDetailResponse;
import SeSAC.Dance_Assessment.Dto.Team.TeamMemberResponse;
import SeSAC.Dance_Assessment.Dto.Team.TeamSummaryResponse;
import SeSAC.Dance_Assessment.Security.CurrentUserId;
import SeSAC.Dance_Assessment.Service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 팀 API. 모두 로그인이 필요하다.
 *
 * <p>오류는 {@code {error, code}}로 응답한다 ({@code ErrorCode}).
 * 404 없음 / 403 멤버·팀장이 아님 / 409 상태 충돌.
 */
@RestController
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    /**
     * 내가 활동 중인 팀 목록.
     *
     * <p>예전에는 이 경로가 <b>전체 팀 목록</b>을 줬다(회원가입에서 팀을 고르게).
     * 지금은 참여 코드로만 들어오므로 다른 팀의 존재를 알려줄 이유가 없다.
     */
    @GetMapping("/teams")
    public ResponseEntity<List<TeamSummaryResponse>> getMyTeams(@CurrentUserId Long userId) {
        return ResponseEntity.ok(teamService.getMyTeams(userId));
    }

    /** 팀 생성. 만든 사람이 팀장이 된다. */
    @PostMapping("/teams")
    public ResponseEntity<TeamSummaryResponse> createTeam(
            @CurrentUserId Long userId,
            @RequestBody TeamCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.createTeam(userId, request.name()));
    }

    /** 참여 전 확인 — 팀 이름, 팀장 닉네임, 인원 수. */
    @PostMapping("/teams/join-preview")
    public ResponseEntity<JoinPreviewResponse> previewJoin(@RequestBody JoinCodeRequest request) {
        return ResponseEntity.ok(teamService.previewJoin(request.code()));
    }

    @PostMapping("/teams/join")
    public ResponseEntity<TeamSummaryResponse> join(
            @CurrentUserId Long userId,
            @RequestBody JoinCodeRequest request) {
        return ResponseEntity.ok(teamService.join(userId, request.code()));
    }

    /** Team Space 상단 한 줄 + 팀 채팅방 id. 활동 중 멤버만. */
    @GetMapping("/teams/{teamId}")
    public ResponseEntity<TeamDetailResponse> getTeam(
            @PathVariable Long teamId,
            @CurrentUserId Long userId) {
        return ResponseEntity.ok(teamService.getTeam(teamId, userId));
    }

    @GetMapping("/teams/{teamId}/members")
    public ResponseEntity<List<TeamMemberResponse>> getMembers(
            @PathVariable Long teamId,
            @CurrentUserId Long userId) {
        return ResponseEntity.ok(teamService.getMembers(teamId, userId));
    }

    /** 참여 코드 — 팀장만. 다른 응답에는 싣지 않는다. */
    @GetMapping("/teams/{teamId}/join-code")
    public ResponseEntity<JoinCodeResponse> getJoinCode(
            @PathVariable Long teamId,
            @CurrentUserId Long userId) {
        return ResponseEntity.ok(teamService.getJoinCode(teamId, userId));
    }

    @PostMapping("/teams/{teamId}/leave")
    public ResponseEntity<Void> leave(
            @PathVariable Long teamId,
            @CurrentUserId Long userId) {
        teamService.leave(teamId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/teams/{teamId}/members/{targetUserId}/kick")
    public ResponseEntity<Void> kick(
            @PathVariable Long teamId,
            @PathVariable Long targetUserId,
            @CurrentUserId Long userId) {
        teamService.kick(teamId, userId, targetUserId);
        return ResponseEntity.noContent().build();
    }
}
