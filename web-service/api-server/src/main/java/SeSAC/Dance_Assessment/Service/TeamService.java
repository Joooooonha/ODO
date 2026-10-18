package SeSAC.Dance_Assessment.Service;

import SeSAC.Dance_Assessment.Common.ApiException;
import SeSAC.Dance_Assessment.Common.Constraints;
import SeSAC.Dance_Assessment.Common.ErrorCode;
import SeSAC.Dance_Assessment.Domain.ChatMemberStatus;
import SeSAC.Dance_Assessment.Domain.Team;
import SeSAC.Dance_Assessment.Domain.TeamMember;
import SeSAC.Dance_Assessment.Domain.TeamMemberStatus;
import SeSAC.Dance_Assessment.Domain.TeamRole;
import SeSAC.Dance_Assessment.Domain.User;
import SeSAC.Dance_Assessment.Dto.Team.JoinCodeResponse;
import SeSAC.Dance_Assessment.Dto.Team.JoinPreviewResponse;
import SeSAC.Dance_Assessment.Dto.Team.TeamDetailResponse;
import SeSAC.Dance_Assessment.Dto.Team.TeamMemberResponse;
import SeSAC.Dance_Assessment.Dto.Team.TeamSummaryResponse;
import SeSAC.Dance_Assessment.Infrastructure.TeamMemberRepository;
import SeSAC.Dance_Assessment.Infrastructure.TeamRepository;
import SeSAC.Dance_Assessment.Infrastructure.UserRepository;
import SeSAC.Dance_Assessment.Service.Chat.ChatRoomService;
import SeSAC.Dance_Assessment.Service.Chat.TeamChatEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 팀 생성·참여·나가기·추방.
 *
 * <p><b>소속은 {@link TeamMember}만 본다.</b> {@code users.team_id}와
 * {@code team.leader_id}는 DB에 남아 있지만 읽지도 쓰지도 않는다.
 *
 * <p><b>상태를 바꾸는 경로(재참여·나가기·추방)는 모두 행 잠금 조회
 * ({@link TeamMemberRepository#findForUpdate})로 시작한다.</b> 같은 행을 두 요청이
 * 동시에 바꾸면 나중에 커밋한 쪽이 앞의 결과를 덮어쓰기 때문이다.
 *
 * <p><b>팀 채팅방은 같은 트랜잭션에서 따라간다.</b> 팀 생성 = 팀 + 팀 채팅방 + 팀장의 채팅 멤버,
 * 참여·재활성화·나가기·추방 = 채팅 멤버 상태 변경({@link ChatRoomService}). 채팅방 생성이 실패하면
 * 팀 생성도 실패한다. 잠금은 {@code team_member} 행을 먼저, 그다음 채팅방 행을 잡는다.
 * 커밋된 <b>뒤에</b> 제어 이벤트(REVOKED·GRANTED — 다른 서버에 붙은 세션까지 그 방만 실시간 차단·허용)를
 * 발행하고 입장·나가기·추방 안내 메시지를 따로 저장한다({@link TeamChatEvent}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamService {

    static final int MAX_NAME_LENGTH = 30;
    private static final int CODE_ATTEMPTS = 5;
    private static final String UK_TEAM_USER = "uk_team_member_team_user";

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final JoinCodeGenerator joinCodeGenerator;
    private final ChatRoomService chatRoomService;
    private final ApplicationEventPublisher events;

    @Transactional
    public TeamSummaryResponse createTeam(Long userId, String rawName) {
        User user = findUser(userId);
        String name = validateName(rawName);

        Team team = teamRepository.save(new Team(name, newJoinCode()));
        teamMemberRepository.save(TeamMember.leader(team, user));
        chatRoomService.createTeamRoom(team.getId(), userId);

        return TeamSummaryResponse.of(team, TeamRole.LEADER, 1);
    }

    /** 내가 활동 중인 팀만. */
    public List<TeamSummaryResponse> getMyTeams(Long userId) {
        List<TeamMember> mine = teamMemberRepository.findMyTeams(userId, TeamMemberStatus.ACTIVE);
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = teamMemberRepository
                .countByTeamIds(mine.stream().map(m -> m.getTeam().getId()).toList(),
                        TeamMemberStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        return mine.stream()
                .map(m -> TeamSummaryResponse.of(
                        m.getTeam(), m.getRole(), counts.getOrDefault(m.getTeam().getId(), 0L)))
                .toList();
    }

    /** Team Space 상단 한 줄 + 채팅 탭이 쓰는 팀 채팅방 id. */
    public TeamDetailResponse getTeam(Long teamId, Long userId) {
        Team team = findTeam(teamId);
        TeamMember me = requireActiveMember(teamId, userId);
        return TeamDetailResponse.of(team, me.getRole(), countActive(teamId),
                chatRoomService.getTeamRoomId(teamId));
    }

    /** 활동 중 멤버만. 팀장이 맨 앞, 나머지는 참여한 순서. */
    public List<TeamMemberResponse> getMembers(Long teamId, Long userId) {
        findTeam(teamId);
        requireActiveMember(teamId, userId);
        return teamMemberRepository.findByTeamWithUser(teamId, TeamMemberStatus.ACTIVE).stream()
                .sorted(Comparator.comparing((TeamMember m) -> !m.isLeader()))
                .map(TeamMemberResponse::from)
                .toList();
    }

    public JoinCodeResponse getJoinCode(Long teamId, Long userId) {
        Team team = findTeam(teamId);
        requireLeader(teamId, userId);
        return new JoinCodeResponse(team.getJoinCode());
    }

    /** 참여 전 확인 화면. 팀 이름, 팀장 닉네임, 활동 중 인원 수. */
    public JoinPreviewResponse previewJoin(String rawCode) {
        Team team = findTeamByCode(rawCode);
        String leaderNickname = teamMemberRepository.findByTeamAndRole(team.getId(), TeamRole.LEADER)
                .map(m -> m.getUser().getNickname())
                .orElse(null);
        return new JoinPreviewResponse(team.getTeamName(), leaderNickname, countActive(team.getId()));
    }

    /**
     * 코드로 참여한다.
     *
     * <ul>
     *   <li>기록 없음 → 새로 등록. 동시에 두 요청이 와도 UNIQUE(team_id, user_id)가
     *       하나만 남기고, 진 쪽은 409를 받는다</li>
     *   <li>활동 중 → 409, 추방됨 → 403</li>
     *   <li>스스로 나감 → 기존 기록을 다시 활성화 (새 기록을 만들지 않는다)</li>
     * </ul>
     */
    @Transactional
    public TeamSummaryResponse join(Long userId, String rawCode) {
        Team team = findTeamByCode(rawCode);
        User user = findUser(userId);

        TeamMember member = teamMemberRepository.findForUpdate(team.getId(), userId).orElse(null);
        if (member == null) {
            member = insertMember(team, user);
        } else {
            switch (member.getStatus()) {
                case ACTIVE -> throw new ApiException(ErrorCode.ALREADY_MEMBER);
                case KICKED -> throw new ApiException(ErrorCode.KICKED_FROM_TEAM);
                case LEFT -> member.reactivate();
            }
        }
        Long roomId = chatRoomService.joinTeamRoom(team.getId(), userId);
        events.publishEvent(TeamChatEvent.of(roomId, userId, TeamChatEvent.Kind.JOIN));

        return TeamSummaryResponse.of(team, member.getRole(), countActive(team.getId()));
    }

    /** 팀원만 나갈 수 있다. 팀장은 나갈 수 없다. */
    @Transactional
    public void leave(Long teamId, Long userId) {
        findTeam(teamId);
        TeamMember me = teamMemberRepository.findForUpdate(teamId, userId)
                .filter(TeamMember::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_TEAM_MEMBER));
        if (me.isLeader()) {
            throw new ApiException(ErrorCode.LEADER_CANNOT_LEAVE);
        }
        me.leave();
        Long roomId = chatRoomService.leaveTeamRoom(teamId, userId, ChatMemberStatus.LEFT);
        events.publishEvent(TeamChatEvent.of(roomId, userId, TeamChatEvent.Kind.LEAVE));
    }

    /** 팀장만 추방할 수 있다. 팀장 자신은 추방할 수 없다. */
    @Transactional
    public void kick(Long teamId, Long leaderId, Long targetUserId) {
        findTeam(teamId);
        requireLeader(teamId, leaderId);
        if (leaderId.equals(targetUserId)) {
            throw new ApiException(ErrorCode.CANNOT_KICK_LEADER);
        }
        TeamMember target = teamMemberRepository.findForUpdate(teamId, targetUserId)
                .filter(TeamMember::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));
        if (target.isLeader()) {
            throw new ApiException(ErrorCode.CANNOT_KICK_LEADER);
        }
        target.kick();
        Long roomId = chatRoomService.leaveTeamRoom(teamId, targetUserId, ChatMemberStatus.KICKED);
        events.publishEvent(TeamChatEvent.of(roomId, targetUserId, TeamChatEvent.Kind.KICK));
    }

    // ===== 권한 =====

    /** 그 팀의 활동 중 멤버인지. 나가거나 추방된 사람은 여기서 막힌다. */
    public TeamMember requireActiveMember(Long teamId, Long userId) {
        return teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .filter(TeamMember::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_TEAM_MEMBER));
    }

    public TeamMember requireLeader(Long teamId, Long userId) {
        TeamMember me = requireActiveMember(teamId, userId);
        if (!me.isLeader()) {
            throw new ApiException(ErrorCode.NOT_TEAM_LEADER);
        }
        return me;
    }

    // ===== 내부 =====

    private TeamMember insertMember(Team team, User user) {
        try {
            return teamMemberRepository.saveAndFlush(TeamMember.member(team, user));
        } catch (DataIntegrityViolationException e) {
            // 같은 사람의 참여 요청이 동시에 와서 다른 요청이 먼저 기록을 만든 경우다.
            // 다른 제약 위반(FK 등)은 원인이 다르므로 그대로 던진다.
            if (Constraints.violates(e, UK_TEAM_USER)) {
                throw new ApiException(ErrorCode.ALREADY_MEMBER);
            }
            throw e;
        }
    }

    private String newJoinCode() {
        // 31^8가지라 겹칠 일은 거의 없다. 겹쳐도 다시 뽑고, 끝까지 겹치면
        // DB UNIQUE(uk_team_join_code)가 마지막으로 막는다.
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = joinCodeGenerator.generate();
            if (!teamRepository.existsByJoinCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("참여 코드를 만들지 못했습니다. 다시 시도해주세요.");
    }

    private static String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("팀 이름을 입력해주세요.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("팀 이름은 " + MAX_NAME_LENGTH + "자 이하로 입력해주세요.");
        }
        return name;
    }

    private long countActive(Long teamId) {
        return teamMemberRepository.countByTeamIdAndStatus(teamId, TeamMemberStatus.ACTIVE);
    }

    private Team findTeam(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new ApiException(ErrorCode.TEAM_NOT_FOUND));
    }

    /** 형식이 맞지 않는 코드도 "없는 코드"로 응답한다. */
    private Team findTeamByCode(String rawCode) {
        String code = JoinCodeGenerator.normalize(rawCode);
        if (code == null) {
            throw new ApiException(ErrorCode.JOIN_CODE_NOT_FOUND);
        }
        return teamRepository.findByJoinCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.JOIN_CODE_NOT_FOUND));
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));
    }
}
