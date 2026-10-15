package SeSAC.Dance_Assessment.Presentation;

import SeSAC.Dance_Assessment.Dto.Mission.CommentRequest;
import SeSAC.Dance_Assessment.Dto.Mission.CommentResponse;
import SeSAC.Dance_Assessment.Dto.Mission.MissionCreateRequest;
import SeSAC.Dance_Assessment.Dto.Mission.MissionCreatedResponse;
import SeSAC.Dance_Assessment.Dto.Mission.MissionDetailResponse;
import SeSAC.Dance_Assessment.Dto.Mission.MissionSummaryResponse;
import SeSAC.Dance_Assessment.Dto.Mission.MissionUpdateRequest;
import SeSAC.Dance_Assessment.Dto.Mission.SubmissionCreateRequest;
import SeSAC.Dance_Assessment.Dto.Mission.SubmissionCreatedResponse;
import SeSAC.Dance_Assessment.Dto.Mission.SubmissionDetailResponse;
import SeSAC.Dance_Assessment.Security.CurrentUserId;
import SeSAC.Dance_Assessment.Service.MissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Mission API. 모두 로그인이 필요하다.
 *
 * <p>오류는 {@code {error, code}}로 응답한다 ({@code ErrorCode}).
 * 404 없음(삭제된 미션은 MISSION_DELETED) / 403 멤버·팀장·대상자가 아님 / 409 이미 제출함.
 * 영상은 미리 {@code POST /videos/upload-url}({@code purpose: MISSION})로 올려 두고 id만 보낸다.
 */
@RestController
@RequiredArgsConstructor
public class MissionController {

    private final MissionService missionService;

    @GetMapping("/teams/{teamId}/missions")
    public ResponseEntity<List<MissionSummaryResponse>> getMissions(
            @PathVariable Long teamId,
            @CurrentUserId Long userId) {
        return ResponseEntity.ok(missionService.getMissions(teamId, userId));
    }

    @PostMapping("/teams/{teamId}/missions")
    public ResponseEntity<MissionCreatedResponse> createMission(
            @PathVariable Long teamId,
            @CurrentUserId Long userId,
            @RequestBody MissionCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MissionCreatedResponse(missionService.create(teamId, userId, request)));
    }

    @GetMapping("/missions/{missionId}")
    public ResponseEntity<MissionDetailResponse> getMission(
            @PathVariable Long missionId,
            @CurrentUserId Long userId) {
        return ResponseEntity.ok(missionService.getMission(missionId, userId));
    }

    /** 제목·안내 텍스트·레퍼런스 영상을 통째로 바꾼다. 대상자 목록은 바꿀 수 없다. */
    @PutMapping("/missions/{missionId}")
    public ResponseEntity<Void> updateMission(
            @PathVariable Long missionId,
            @CurrentUserId Long userId,
            @RequestBody MissionUpdateRequest request) {
        missionService.update(missionId, userId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/missions/{missionId}")
    public ResponseEntity<Void> deleteMission(
            @PathVariable Long missionId,
            @CurrentUserId Long userId) {
        missionService.delete(missionId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/missions/{missionId}/submissions")
    public ResponseEntity<SubmissionCreatedResponse> submit(
            @PathVariable Long missionId,
            @CurrentUserId Long userId,
            @RequestBody SubmissionCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SubmissionCreatedResponse(missionService.submit(missionId, userId, request)));
    }

    /** 제출물 보기 — 영상, 텍스트, 코멘트 목록. */
    @GetMapping("/submissions/{submissionId}")
    public ResponseEntity<SubmissionDetailResponse> getSubmission(
            @PathVariable Long submissionId,
            @CurrentUserId Long userId) {
        return ResponseEntity.ok(missionService.getSubmission(submissionId, userId));
    }

    @PostMapping("/submissions/{submissionId}/comments")
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable Long submissionId,
            @CurrentUserId Long userId,
            @RequestBody CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(missionService.addComment(submissionId, userId, request.content()));
    }
}
