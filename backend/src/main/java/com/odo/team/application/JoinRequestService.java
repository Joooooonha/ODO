package com.odo.team.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.odo.team.application.dto.InvitePreviewResult;
import com.odo.team.application.dto.InviteStatus;
import com.odo.team.application.dto.JoinRequestResult;
import com.odo.team.application.dto.PendingJoinRequestResult;
import com.odo.team.application.dto.TeamMemberResult;
import com.odo.team.domain.InviteNotFoundException;
import com.odo.team.domain.JoinRequest;
import com.odo.team.domain.JoinRequestRepository;
import com.odo.team.domain.JoinRequestStatus;
import com.odo.team.domain.Team;
import com.odo.team.domain.TeamMemberRepository;
import com.odo.team.domain.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초대와 입장 요청. 02_TeamSpace_spec.md의 "초대와 입장", "입장 요청 상태".
 * 조회(초대 확인, 내 요청 목록, 대기 목록)는 Claude, 상태를 바꾸는 동작(요청 보내기, 승인, 거절, 취소)은 사용자 담당이다.
 */
@Service
@RequiredArgsConstructor
public class JoinRequestService {

	private final TeamRepository teamRepository;

	private final TeamMemberRepository teamMemberRepository;

	private final JoinRequestRepository joinRequestRepository;

	private final TeamAccess teamAccess;

	private final NameLookup nameLookup;

	/** 초대 링크를 연 사람에게 보여 줄 팀 정보와 내 상태. 무효인 코드면 InviteNotFoundException. */
	@Transactional(readOnly = true)
	public InvitePreviewResult preview(long accountId, String inviteCode) {
		Team team = teamRepository.findByInviteCode(inviteCode).orElseThrow(InviteNotFoundException::new);
		String leaderName = nameLookup.leaderNameOf(team.getId());
		if (teamMemberRepository.findByTeamIdAndAccountId(team.getId(), accountId).isPresent()) {
			return preview(team, leaderName, InviteStatus.MEMBER, null);
		}
		return joinRequestRepository
			.findByTeamIdAndAccountIdAndStatus(team.getId(), accountId, JoinRequestStatus.PENDING)
			.map(pending -> preview(team, leaderName, InviteStatus.PENDING, pending.getId()))
			.orElseGet(() -> preview(team, leaderName, InviteStatus.NONE, null));
	}

	/** 내 대기 중인 요청만, 오래된 것부터. */
	@Transactional(readOnly = true)
	public List<JoinRequestResult> myRequests(long accountId) {
		List<JoinRequest> requests = joinRequestRepository
			.findAllByAccountIdAndStatusOrderByRequestedAtAscIdAsc(accountId, JoinRequestStatus.PENDING);
		Map<Long, Team> teams = teamRepository.findAllById(requests.stream().map(JoinRequest::getTeamId).toList())
			.stream()
			.collect(Collectors.toMap(Team::getId, Function.identity()));
		return requests.stream().map(request -> JoinRequestResult.of(request, teams.get(request.getTeamId()))).toList();
	}

	/** 팀장의 대기 목록. 대기 중인 요청만, 오래된 것부터. */
	@Transactional(readOnly = true)
	public List<PendingJoinRequestResult> pendingRequests(long leaderId, long teamId) {
		teamAccess.requireLeader(teamId, leaderId);
		List<JoinRequest> requests = joinRequestRepository.findAllByTeamIdAndStatusOrderByRequestedAtAscIdAsc(teamId,
				JoinRequestStatus.PENDING);
		Map<Long, String> names = nameLookup.namesOf(requests.stream().map(JoinRequest::getAccountId).toList());
		return requests.stream()
			.map(request -> PendingJoinRequestResult.of(request, names.get(request.getAccountId())))
			.toList();
	}

	// ---- 아래는 사용자 담당. 시그니처는 spec의 "사용자 담당 시그니처"를 따르고 본문은 사용자가 쓴다 ----

	/**
	 * 입장 요청 보내기.
	 * 무효인 코드 InviteNotFoundException, 기수 오류 InvalidGenerationException,
	 * 이미 팀원 AlreadyMemberException, 대기 중인 요청이 있음 AlreadyRequestedException.
	 */
	public JoinRequestResult request(long accountId, String inviteCode, Integer generation) {
		throw new UnsupportedOperationException("사용자 구현 예정");
	}

	/**
	 * 승인하고 요청한 기수로 팀원으로 넣는다.
	 * 팀원 아님 TeamNotFoundException, 팀장 아님 NotTeamLeaderException,
	 * 이 팀의 요청이 아님 RequestNotFoundException, 이미 처리됨 RequestNotPendingException.
	 */
	public TeamMemberResult approve(long leaderId, long teamId, long requestId) {
		throw new UnsupportedOperationException("사용자 구현 예정");
	}

	/** 거절. 예외는 승인과 같다. */
	public void reject(long leaderId, long teamId, long requestId) {
		throw new UnsupportedOperationException("사용자 구현 예정");
	}

	/** 내 요청 취소. 남의 요청이면 RequestNotFoundException, 이미 처리됨 RequestNotPendingException. */
	public void cancel(long accountId, long requestId) {
		throw new UnsupportedOperationException("사용자 구현 예정");
	}

	private static InvitePreviewResult preview(Team team, String leaderName, InviteStatus status, Long requestId) {
		return new InvitePreviewResult(team.getId(), team.getName(), leaderName, team.getDescription(), status,
				requestId);
	}

}
