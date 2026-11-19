package com.odo.team.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.odo.team.application.dto.InvitePreviewResult;
import com.odo.team.application.dto.InviteStatus;
import com.odo.team.application.dto.JoinRequestResult;
import com.odo.team.application.dto.PendingJoinRequestResult;
import com.odo.team.application.dto.TeamMemberResult;
import com.odo.team.domain.entity.JoinRequest;
import com.odo.team.domain.entity.JoinRequestStatus;
import com.odo.team.domain.entity.Team;
import com.odo.team.domain.entity.TeamMember;
import com.odo.team.domain.exception.AlreadyMemberException;
import com.odo.team.domain.exception.AlreadyRequestedException;
import com.odo.team.domain.exception.InviteNotFoundException;
import com.odo.team.domain.exception.RequestNotFoundException;
import com.odo.team.domain.exception.RequestNotPendingException;
import com.odo.team.domain.repository.JoinRequestRepository;
import com.odo.team.domain.repository.TeamMemberRepository;
import com.odo.team.domain.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초대와 입장 요청. 02_TeamSpace_spec.md의 "초대와 입장", "입장 요청 상태".
 * 상태를 바꾸는 동작(승인, 거절, 취소)은 조건부 UPDATE로 해서 동시에 와도 하나만 반영된다.
 */
@Service
@RequiredArgsConstructor
public class JoinRequestService {

	/** 한 사람이 한 팀에 대기 중인 요청을 하나만 갖게 하는 부분 유니크 인덱스. V2 마이그레이션. */
	private static final String PENDING_UNIQUE_INDEX = "uk_join_request_pending";

	private final TeamRepository teamRepository;

	private final TeamMemberRepository teamMemberRepository;

	private final JoinRequestRepository joinRequestRepository;

	private final TeamAccess teamAccess;

	private final NameLookup nameLookup;

	private final Clock clock;

	/** 초대 링크를 연 사람에게 보여 줄 팀 정보와 내 상태. 무효인 코드면 InviteNotFoundException. */
	@Transactional(readOnly = true)
	public InvitePreviewResult previewInvite(long accountId, String inviteCode) {
		Team team = teamRepository.findByInviteCode(inviteCode).orElseThrow(InviteNotFoundException::new);
		String leaderName = nameLookup.leaderNameOf(team.getId());
		if (teamMemberRepository.findByTeamIdAndAccountId(team.getId(), accountId).isPresent()) {
			return toPreviewResult(team, leaderName, InviteStatus.MEMBER, null);
		}
		return joinRequestRepository
			.findByTeamIdAndAccountIdAndStatus(team.getId(), accountId, JoinRequestStatus.PENDING)
			.map(pending -> toPreviewResult(team, leaderName, InviteStatus.PENDING, pending.getId()))
			.orElseGet(() -> toPreviewResult(team, leaderName, InviteStatus.NONE, null));
	}

	/** 내 대기 중인 요청만, 오래된 것부터. */
	@Transactional(readOnly = true)
	public List<JoinRequestResult> findMyJoinRequests(long accountId) {
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

	/**
	 * 입장 요청 보내기.
	 * 무효인 코드 InviteNotFoundException, 기수 오류 InvalidGenerationException,
	 * 이미 팀원 AlreadyMemberException, 대기 중인 요청이 있음 AlreadyRequestedException.
	 */
	@Transactional
	public JoinRequestResult requestJoin(long accountId, String inviteCode, Integer generation) {
		Team team = teamRepository.findByInviteCode(inviteCode).orElseThrow(InviteNotFoundException::new);
		JoinRequest request = JoinRequest.createPending(team.getId(), accountId, generation, clock.instant());
		if (teamMemberRepository.findByTeamIdAndAccountId(team.getId(), accountId).isPresent()) {
			throw new AlreadyMemberException();
		}
		if (joinRequestRepository.findByTeamIdAndAccountIdAndStatus(team.getId(), accountId, JoinRequestStatus.PENDING)
			.isPresent()) {
			throw new AlreadyRequestedException();
		}
		// 위 확인은 흔한 경우를 먼저 걸러 낼 뿐이다. 동시에 들어온 요청은 둘 다 확인을 통과할 수 있고,
		// 그때는 DB의 부분 유니크 인덱스가 하나만 남긴다. 그 위반을 같은 409로 바꾼다.
		try {
			joinRequestRepository.saveAndFlush(request);
		}
		catch (DataIntegrityViolationException ex) {
			if (violates(ex, PENDING_UNIQUE_INDEX)) {
				throw new AlreadyRequestedException();
			}
			throw ex;
		}
		return JoinRequestResult.of(request, team);
	}

	/**
	 * 승인하고 요청한 기수로 팀원으로 넣는다.
	 * 팀원 아님 TeamNotFoundException, 팀장 아님 NotTeamLeaderException,
	 * 이 팀의 요청이 아님 RequestNotFoundException, 이미 처리됨 RequestNotPendingException.
	 */
	@Transactional
	public TeamMemberResult approve(long leaderId, long teamId, long requestId) {
		JoinRequest request = decide(leaderId, teamId, requestId, JoinRequestStatus.APPROVED);
		// 조건부 UPDATE가 이 요청 행을 잠근 채로 팀원을 넣는다. 상태 변경과 팀원 추가는 같은 트랜잭션이라 함께 반영되거나 함께 취소된다.
		TeamMember member = teamMemberRepository
			.save(TeamMember.createMember(teamId, request.getAccountId(), request.getGeneration(), clock.instant()));
		return TeamMemberResult.of(member, nameLookup.nameOf(member.getAccountId()));
	}

	/** 거절. 예외는 승인과 같다. */
	@Transactional
	public void reject(long leaderId, long teamId, long requestId) {
		decide(leaderId, teamId, requestId, JoinRequestStatus.REJECTED);
	}

	/** 내 요청 취소. 남의 요청이면 RequestNotFoundException, 이미 처리됨 RequestNotPendingException. */
	@Transactional
	public void cancel(long accountId, long requestId) {
		JoinRequest request = joinRequestRepository.findById(requestId)
			.filter(found -> found.getAccountId() == accountId)
			.orElseThrow(RequestNotFoundException::new);
		changeStatus(request, JoinRequestStatus.CANCELED);
	}

	/** 팀장이 이 팀의 요청을 승인하거나 거절한다. 상태를 바꾼 요청을 돌려준다(엔티티의 status는 갱신 전 값이다). */
	private JoinRequest decide(long leaderId, long teamId, long requestId, JoinRequestStatus status) {
		teamAccess.requireLeader(teamId, leaderId);
		JoinRequest request = joinRequestRepository.findById(requestId)
			.filter(found -> found.getTeamId() == teamId)
			.orElseThrow(RequestNotFoundException::new);
		changeStatus(request, status);
		return request;
	}

	/**
	 * 대기 중일 때만 바꾼다. 0행이면 이미 처리된 요청이다.
	 * 동시에 두 트랜잭션이 같은 행을 바꾸면 뒤의 것은 앞의 것이 끝날 때까지 기다린 뒤 조건을 다시 보고 0행이 된다.
	 */
	private void changeStatus(JoinRequest request, JoinRequestStatus status) {
		if (joinRequestRepository.changeStatusIfPending(request.getId(), status, clock.instant()) == 0) {
			throw new RequestNotPendingException();
		}
	}

	/** 예외 원인을 따라가며 위반된 제약 이름을 찾는다. */
	private static boolean violates(DataIntegrityViolationException ex, String constraintName) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return constraintName.equalsIgnoreCase(violation.getConstraintName());
			}
		}
		return false;
	}

	private static InvitePreviewResult toPreviewResult(Team team, String leaderName, InviteStatus status, Long requestId) {
		return new InvitePreviewResult(team.getId(), team.getName(), leaderName, team.getDescription(), status,
				requestId);
	}

}
