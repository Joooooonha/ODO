package SeSAC.Dance_Assessment.Service;

import SeSAC.Dance_Assessment.Common.ApiException;
import SeSAC.Dance_Assessment.Common.Constraints;
import SeSAC.Dance_Assessment.Common.ErrorCode;
import SeSAC.Dance_Assessment.Domain.Mission;
import SeSAC.Dance_Assessment.Domain.MissionSubmission;
import SeSAC.Dance_Assessment.Domain.MissionTarget;
import SeSAC.Dance_Assessment.Domain.SubmissionComment;
import SeSAC.Dance_Assessment.Domain.Team;
import SeSAC.Dance_Assessment.Domain.TeamMember;
import SeSAC.Dance_Assessment.Domain.User;
import SeSAC.Dance_Assessment.Domain.Video;
import SeSAC.Dance_Assessment.Domain.VideoType;
import SeSAC.Dance_Assessment.Dto.ApiTime;
import SeSAC.Dance_Assessment.Dto.Mission.CommentResponse;
import SeSAC.Dance_Assessment.Dto.Mission.MissionCreateRequest;
import SeSAC.Dance_Assessment.Dto.Mission.MissionDetailResponse;
import SeSAC.Dance_Assessment.Dto.Mission.MissionSubmitStatus;
import SeSAC.Dance_Assessment.Dto.Mission.MissionSummaryResponse;
import SeSAC.Dance_Assessment.Dto.Mission.MissionTargetResponse;
import SeSAC.Dance_Assessment.Dto.Mission.MissionUpdateRequest;
import SeSAC.Dance_Assessment.Dto.Mission.SubmissionCreateRequest;
import SeSAC.Dance_Assessment.Dto.Mission.SubmissionDetailResponse;
import SeSAC.Dance_Assessment.Infrastructure.MissionRepository;
import SeSAC.Dance_Assessment.Infrastructure.MissionSubmissionRepository;
import SeSAC.Dance_Assessment.Infrastructure.MissionTargetRepository;
import SeSAC.Dance_Assessment.Infrastructure.SubmissionCommentRepository;
import SeSAC.Dance_Assessment.Infrastructure.TeamMemberRepository;
import SeSAC.Dance_Assessment.Infrastructure.TeamRepository;
import SeSAC.Dance_Assessment.Infrastructure.UserRepository;
import SeSAC.Dance_Assessment.Infrastructure.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mission — 생성·조회·수정·삭제, 제출, 제출물 코멘트.
 *
 * <p><b>권한은 팀 멤버십({@link TeamService#requireActiveMember}, {@link TeamService#requireLeader})으로만
 * 판단한다.</b> 조회는 그 팀의 활동 중 멤버 누구나(대상자가 아니어도), 생성·수정·삭제는 팀장,
 * 제출은 활동 중인 대상자, 코멘트는 팀장과 그 제출자.
 *
 * <p>판정 순서는 "미션이 있는가(404) → 그 팀의 활동 중 멤버인가(403) → 삭제됐는가(404 MISSION_DELETED)"다.
 * 팀 밖 사람에게는 삭제 여부도 알려주지 않는다.
 *
 * <p>Mission 영상은 분석하지 않는다. 이 서비스는 분석 서버·연습 기록을 전혀 건드리지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionService {

    static final int MAX_TITLE = 50;
    static final int MAX_TEXT = 1000;
    static final int MAX_COMMENT = 500;
    private static final String UK_SUBMISSION_TARGET = "uk_mission_submission_target";

    private final MissionRepository missionRepository;
    private final MissionTargetRepository targetRepository;
    private final MissionSubmissionRepository submissionRepository;
    private final SubmissionCommentRepository commentRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final VideoRepository videoRepository;
    private final TeamService teamService;

    // ===== 조회 =====

    /**
     * 미션 탭 목록. 숫자(제출 수·대상자 수·내 상태)는 미션마다 세지 않고 집계 쿼리 한 번으로 구한다.
     * 실행되는 SQL: 팀 확인 1 + 멤버 확인 1 + 미션 목록 1 + 집계 1.
     */
    public List<MissionSummaryResponse> getMissions(Long teamId, Long userId) {
        findTeam(teamId);
        teamService.requireActiveMember(teamId, userId);

        List<Mission> missions = missionRepository.findActiveByTeam(teamId);
        if (missions.isEmpty()) {
            return List.of();
        }
        Map<Long, Object[]> stats = targetRepository
                .summarize(missions.stream().map(Mission::getId).toList(), userId).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], Function.identity()));

        return missions.stream().map(m -> {
            Object[] row = stats.get(m.getId());
            long targets = row == null ? 0 : (Long) row[1];
            long submitted = row == null ? 0 : (Long) row[2];
            boolean amTarget = row != null && ((Number) row[3]).longValue() > 0;
            boolean amSubmitted = row != null && ((Number) row[4]).longValue() > 0;
            MissionSubmitStatus my = !amTarget ? null
                    : amSubmitted ? MissionSubmitStatus.SUBMITTED : MissionSubmitStatus.NOT_SUBMITTED;
            return new MissionSummaryResponse(m.getId(), m.getTitle(), submitted, targets, my,
                    ApiTime.utc(m.getCreatedAt()));
        }).toList();
    }

    public MissionDetailResponse getMission(Long missionId, Long userId) {
        Mission mission = findMission(missionId);
        TeamMember me = requireMemberOfLiveMission(mission, userId);

        Map<Long, Long> submissionByTarget = submissionRepository.findByMission(missionId).stream()
                .collect(Collectors.toMap(s -> s.getTarget().getId(), MissionSubmission::getId));
        List<MissionTarget> targets = targetRepository.findByMissionWithUser(missionId);

        MissionSubmitStatus my = targets.stream()
                .filter(t -> t.getUser().getId().equals(userId))
                .findFirst()
                .map(t -> submissionByTarget.containsKey(t.getId())
                        ? MissionSubmitStatus.SUBMITTED : MissionSubmitStatus.NOT_SUBMITTED)
                .orElse(null);

        return new MissionDetailResponse(
                mission.getId(),
                mission.getTeam().getId(),
                mission.getTitle(),
                mission.getDescription(),
                mission.getReferenceVideo().getId(),
                mission.getWriter().getNickname(),
                ApiTime.utc(mission.getCreatedAt()),
                me.getRole(),
                my,
                targets.stream()
                        .map(t -> new MissionTargetResponse(t.getUser().getId(), t.getUser().getNickname(),
                                submissionByTarget.get(t.getId())))
                        .toList());
    }

    public SubmissionDetailResponse getSubmission(Long submissionId, Long userId) {
        MissionSubmission submission = findSubmission(submissionId);
        TeamMember me = requireMemberOfLiveMission(submission.getTarget().getMission(), userId);
        return toDetail(submission, me, userId);
    }

    // ===== 생성·수정·삭제 (팀장) =====

    @Transactional
    public Long create(Long teamId, Long userId, MissionCreateRequest request) {
        Team team = findTeam(teamId);
        TeamMember leader = teamService.requireLeader(teamId, userId);

        String title = validateTitle(request.title());
        String description = optionalText(request.description(), MAX_TEXT, "안내 텍스트");
        Video reference = requireMissionVideo(request.referenceVideoId(), userId, VideoType.REFERENCE,
                "레퍼런스 영상");
        List<User> targets = validateTargets(teamId, request.targetUserIds());

        Mission mission = missionRepository.save(
                new Mission(team, leader.getUser(), title, description, reference));
        targets.forEach(user -> targetRepository.save(new MissionTarget(mission, user)));
        // TODO(chat): 팀 채팅방에 시스템 카드 게시, 대상자 멘션 알림

        return mission.getId();
    }

    /** 제목·안내 텍스트·레퍼런스 영상만 바꾼다. 이미 제출된 제출물은 자기 영상을 따로 들고 있어 영향이 없다. */
    @Transactional
    public void update(Long missionId, Long userId, MissionUpdateRequest request) {
        Mission mission = findMission(missionId);
        requireLeaderOfLiveMission(mission, userId);

        String title = validateTitle(request.title());
        String description = optionalText(request.description(), MAX_TEXT, "안내 텍스트");
        Video reference = mission.getReferenceVideo().getId().equals(request.referenceVideoId())
                ? mission.getReferenceVideo()
                : requireMissionVideo(request.referenceVideoId(), userId, VideoType.REFERENCE, "레퍼런스 영상");

        boolean titleChanged = !mission.getTitle().equals(title);
        mission.update(title, description, reference);
        if (titleChanged) {
            // TODO(chat): 카드에 제목 반영
        }
    }

    /** soft delete. 행은 남고 목록·상세·제출물·코멘트에서 사라진다. */
    @Transactional
    public void delete(Long missionId, Long userId) {
        Mission mission = findMission(missionId);
        requireLeaderOfLiveMission(mission, userId);
        mission.delete();
        // TODO(chat): 카드 상태 반영 (카드는 남고, 눌러도 이동하지 않음)
    }

    // ===== 제출·코멘트 =====

    /**
     * 대상자의 제출. 1인당 1회이며 재제출은 없다.
     *
     * <p>같은 제출이 동시에 와도 한 건만 남는다 — {@code uk_mission_submission_target}이 막고,
     * 진 쪽은 409 ALREADY_SUBMITTED를 받는다. 다른 제약 위반은 원인이 다르므로 그대로 던진다.
     *
     * <p><b>제출과 추방의 동시 경쟁은 막지 않는다(D16).</b> 제출은 멤버 행을 잠그지 않으므로,
     * 추방이 커밋되는 순간과 겹친 제출은 들어갈 수 있다. 그런 제출은 "추방보다 먼저 시작된 것"으로 본다.
     * 받아들이는 이유: (1) 결과가 규칙과 어긋나지 않는다 — 나간·추방된 대상자의 제출 현황은
     * 직전 상태 그대로 남는 것이 원래 규칙이라, 경계에서 들어온 제출 한 건은 "추방 직전에 낸 제출"과
     * 구분되지 않는다. (2) 막으려면 모든 제출이 멤버 행을 잠가야 하고, 그러면 제출과 추방·나가기·재참여가
     * 서로를 기다리게 된다. 드문 경계 상황 하나를 위해 그 비용을 모든 제출에 물리지 않는다.
     */
    @Transactional
    public Long submit(Long missionId, Long userId, SubmissionCreateRequest request) {
        Mission mission = findMission(missionId);
        requireMemberOfLiveMission(mission, userId);

        MissionTarget target = targetRepository.findByMissionIdAndUserId(missionId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_MISSION_TARGET));
        if (submissionRepository.existsByTargetId(target.getId())) {
            throw new ApiException(ErrorCode.ALREADY_SUBMITTED);
        }
        Video video = requireMissionVideo(request.videoId(), userId, VideoType.PRACTICE, "제출 영상");
        String content = optionalText(request.content(), MAX_TEXT, "제출 텍스트");

        MissionSubmission saved;
        try {
            saved = submissionRepository.saveAndFlush(new MissionSubmission(target, video, content));
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violates(e, UK_SUBMISSION_TARGET)) {
                throw new ApiException(ErrorCode.ALREADY_SUBMITTED);
            }
            throw e;
        }
        // TODO(chat): 카드의 제출 현황 반영
        return saved.getId();
    }

    /** 코멘트는 그 팀의 팀장과 그 제출물의 제출자만 쓴다. 둘 다 활동 중이어야 한다. */
    @Transactional
    public CommentResponse addComment(Long submissionId, Long userId, String rawContent) {
        MissionSubmission submission = findSubmission(submissionId);
        TeamMember me = requireMemberOfLiveMission(submission.getTarget().getMission(), userId);
        if (!canComment(submission, me, userId)) {
            throw new ApiException(ErrorCode.COMMENT_NOT_ALLOWED);
        }
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("코멘트를 입력해주세요.");
        }
        if (content.length() > MAX_COMMENT) {
            throw new IllegalArgumentException("코멘트는 " + MAX_COMMENT + "자 이하로 입력해주세요.");
        }
        return CommentResponse.from(commentRepository.save(new SubmissionComment(submission, me.getUser(), content)));
    }

    // ===== 권한 =====

    /** 그 미션 팀의 활동 중 멤버이고, 미션이 삭제되지 않았는가. 순서가 중요하다(클래스 주석). */
    private TeamMember requireMemberOfLiveMission(Mission mission, Long userId) {
        TeamMember me = teamService.requireActiveMember(mission.getTeam().getId(), userId);
        if (mission.isDeleted()) {
            throw new ApiException(ErrorCode.MISSION_DELETED);
        }
        return me;
    }

    private void requireLeaderOfLiveMission(Mission mission, Long userId) {
        TeamMember me = requireMemberOfLiveMission(mission, userId);
        if (!me.isLeader()) {
            throw new ApiException(ErrorCode.NOT_TEAM_LEADER);
        }
    }

    private static boolean canComment(MissionSubmission submission, TeamMember me, Long userId) {
        return me.isLeader() || submission.getTarget().getUser().getId().equals(userId);
    }

    // ===== 검증 =====

    /**
     * 대상자: 1명 이상, 중복 없음, 모두 그 팀의 <b>활동 중 팀원</b>(팀장 제외).
     * 나가거나 추방된 사람은 후보가 아니다.
     */
    private List<User> validateTargets(Long teamId, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            throw new IllegalArgumentException("대상자를 1명 이상 선택해주세요.");
        }
        Set<Long> seen = new HashSet<>();
        for (Long id : userIds) {
            if (id == null || !seen.add(id)) {
                throw new IllegalArgumentException("대상자 목록이 올바르지 않습니다.");
            }
        }
        return userIds.stream().map(id -> teamMemberRepository.findByTeamIdAndUserId(teamId, id)
                        .filter(m -> m.isActive() && !m.isLeader())
                        .map(TeamMember::getUser)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "팀의 활동 중인 팀원만 대상자로 지정할 수 있습니다(팀장 제외).")))
                .toList();
    }

    /**
     * Mission에 거는 영상: 본인이 올렸고, 용도 MISSION, 종류가 맞고(레퍼런스=REFERENCE, 제출=PRACTICE),
     * 업로드가 끝난 것. 같은 영상을 다른 미션에 다시 거는 것은 막지 않는다 — 재생 권한은
     * "그 영상을 건 미션의 팀"으로 판단하므로, 업로더가 고른 대로 보인다.
     */
    private Video requireMissionVideo(Long videoId, Long userId, VideoType type, String label) {
        if (videoId == null) {
            throw new IllegalArgumentException(label + "을 올려주세요.");
        }
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException(label + "을 찾을 수 없습니다."));
        if (!video.getUploader().getId().equals(userId) || !video.isMission()
                || video.getType() != type || !video.isUploadCompleted()) {
            throw new IllegalArgumentException(label + "으로 쓸 수 없는 영상입니다.");
        }
        return video;
    }

    private static String validateTitle(String raw) {
        String title = raw == null ? "" : raw.strip();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("제목을 입력해주세요.");
        }
        if (title.length() > MAX_TITLE) {
            throw new IllegalArgumentException("제목은 " + MAX_TITLE + "자 이하로 입력해주세요.");
        }
        return title;
    }

    /** 선택 입력. 비어 있으면 null. */
    private static String optionalText(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        if (text.length() > max) {
            throw new IllegalArgumentException(label + "는 " + max + "자 이하로 입력해주세요.");
        }
        return text;
    }

    // ===== 조회 도우미 =====

    private SubmissionDetailResponse toDetail(MissionSubmission s, TeamMember me, Long userId) {
        User submitter = s.getTarget().getUser();
        return new SubmissionDetailResponse(
                s.getId(),
                s.getTarget().getMission().getId(),
                submitter.getId(),
                submitter.getNickname(),
                s.getVideo().getId(),
                s.getContent(),
                ApiTime.utc(s.getCreatedAt()),
                canComment(s, me, userId),
                commentRepository.findBySubmissionWithWriter(s.getId()).stream()
                        .map(CommentResponse::from).toList());
    }

    private Team findTeam(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new ApiException(ErrorCode.TEAM_NOT_FOUND));
    }

    private Mission findMission(Long missionId) {
        return missionRepository.findById(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
    }

    private MissionSubmission findSubmission(Long submissionId) {
        return submissionRepository.findWithMission(submissionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUBMISSION_NOT_FOUND));
    }
}
