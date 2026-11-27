package com.odo.team.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.odo.account.application.AccountReader;
import com.odo.team.application.dto.InvitePreviewResult;
import com.odo.team.application.dto.InviteStatus;
import com.odo.team.application.dto.JoinRequestResult;
import com.odo.team.application.dto.PendingJoinRequestResult;
import com.odo.team.application.dto.TeamMemberResult;
import com.odo.team.domain.entity.JoinRequest;
import com.odo.team.domain.entity.JoinRequestStatus;
import com.odo.team.domain.entity.Team;
import com.odo.team.domain.entity.TeamMember;
import com.odo.team.domain.event.JoinRequestApprovedEvent;
import com.odo.team.domain.event.JoinRequestRejectedEvent;
import com.odo.team.domain.event.JoinRequestedEvent;
import com.odo.team.domain.event.TeamMemberJoinedEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초대와 입장 요청. 02_TeamSpace_spec.md의 "초대와 입장", "입장 요청 상태".
 * 상태를 바꾸는 동작(승인, 거절, 취소)은 조건부 UPDATE로 해서 동시에 와도 하나만 반영된다.
 * 요청, 승인, 거절은 같은 트랜잭션 안에서 사실 이벤트를 발행하고, 알림 기능이 받아 알림으로 바꾼다. 취소는 발행하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class JoinRequestService {

	/** 한 사람이 한 팀에 대기 중인 요청을 하나만 갖게 하는 부분 유니크 인덱스. V2 마이그레이션. */
	private static final String PENDING_UNIQUE_INDEX = "uk_join_request_pending";

	/** 한 사람이 한 팀에 한 번만 들어가게 하는 팀원 유니크 제약. V2 마이그레이션. */
	private static final String MEMBER_UNIQUE_CONSTRAINT = "uk_team_member_team_account";

	private final TeamRepository teamRepository;

	private final TeamMemberRepository teamMemberRepository;

	private final JoinRequestRepository joinRequestRepository;

	private final TeamAccess teamAccess;

	private final TeamMemberReader teamMemberReader;

	private final AccountReader accountReader;

	private final ApplicationEventPublisher eventPublisher;

	private final Clock clock;

	/** 초대 링크를 연 사람에게 보여 줄 팀 정보와 내 상태. 무효인 코드면 InviteNotFoundException. */
	@Transactional(readOnly = true)
	public InvitePreviewResult previewInvite(long accountId, String inviteCode) {
		Team team = teamRepository.findByInviteCode(inviteCode).orElseThrow(InviteNotFoundException::new);
		String leaderName = accountReader.nameOf(teamMemberReader.leaderIdOf(team.getId()));
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
	public List<PendingJoinRequestResult> findPendingJoinRequests(long leaderId, long teamId) {
		teamAccess.requireLeader(teamId, leaderId);
		List<JoinRequest> requests = joinRequestRepository.findAllByTeamIdAndStatusOrderByRequestedAtAscIdAsc(teamId,
				JoinRequestStatus.PENDING);
		Map<Long, String> names = accountReader.namesOf(requests.stream().map(JoinRequest::getAccountId).toList());
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
		// 대기 중인 요청을 먼저, 팀원인지를 나중에 본다. 이 순서를 바꾸지 않는다.
		// 반대 순서면 두 확인 사이에 이전 요청의 승인이 커밋될 때 둘 다 통과해서, 팀원이면서 대기 중인 요청이 있는 상태가 생긴다.
		// 승인은 상태 변경과 팀원 추가를 함께 커밋하므로, 이 순서면 승인 전에는 첫 확인에, 승인 후에는 두 번째 확인에 걸린다.
		if (joinRequestRepository.findByTeamIdAndAccountIdAndStatus(team.getId(), accountId, JoinRequestStatus.PENDING)
			.isPresent()) {
			throw new AlreadyRequestedException();
		}
		if (teamMemberRepository.findByTeamIdAndAccountId(team.getId(), accountId).isPresent()) {
			throw new AlreadyMemberException();
		}
		// 같은 사람의 요청이 동시에 들어오면 둘 다 위 확인을 통과할 수 있다.
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
		eventPublisher.publishEvent(new JoinRequestedEvent(team.getId(), request.getId(), accountId,
				teamMemberReader.leaderIdOf(team.getId())));
		return JoinRequestResult.of(request, team);
	}

	/**
	 * 승인하고 요청한 기수로 팀원으로 넣는다.
	 * 팀원 아님 TeamNotFoundException, 팀장 아님 NotTeamLeaderException,
	 * 이 팀의 요청이 아님 RequestNotFoundException, 이미 처리됨 RequestNotPendingException,
	 * 요청한 사람이 이미 팀원 AlreadyMemberException(이때 요청은 대기 중으로 남는다).
	 * 팀원을 넣은 뒤 같은 트랜잭션에서 TeamMemberJoinedEvent를 발행한다. 받는 쪽(일정)이 실패하면 승인도 롤백된다.
	 */
	@Transactional
	public TeamMemberResult approve(long leaderId, long teamId, long requestId) {
		// 맨 먼저 팀 행을 FOR NO KEY UPDATE로 잠근다. 일정 만들기도 팀원 목록을 읽기 전에 같은 행을 잠그므로 둘이 한 번에 하나씩 돈다.
		// FK 확인(FOR KEY SHARE)과는 충돌하지 않는 잠금이라 알림 기록 같은 INSERT를 막지 않는다(TeamRepository.lockById).
		// 그래서 일정 만들기는 이 승인이 넣은 팀원을 보거나, 승인 쪽 리스너가 그 일정을 본다(03_Schedule_spec.md의 "참여자 행과 새 팀원").
		teamRepository.lockById(teamId);
		JoinRequest request = decide(leaderId, teamId, requestId, JoinRequestStatus.APPROVED);
		// 조건부 UPDATE가 이 요청 행을 잠근 채로 팀원을 넣는다. 상태 변경과 팀원 추가는 같은 트랜잭션이라 함께 반영되거나 함께 취소된다.
		TeamMember member;
		try {
			member = teamMemberRepository
				.save(TeamMember.createMember(teamId, request.getAccountId(), request.getGeneration(), clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			// 팀원이면서 대기 중인 요청이 있는 상태는 경합으로만 생긴다(requestJoin의 확인 순서 참고).
			// 예외로 트랜잭션이 롤백되어 요청은 대기 중으로 남고, 팀장은 거절해서 정리한다.
			if (violates(ex, MEMBER_UNIQUE_CONSTRAINT)) {
				throw new AlreadyMemberException();
			}
			throw ex;
		}
		eventPublisher.publishEvent(new TeamMemberJoinedEvent(teamId, member.getAccountId()));
		// 팀원이 생긴 것과 요청이 승인된 것은 다른 사실이라 따로 발행한다(11_Notification_spec.md의 "알림 기록 방식").
		eventPublisher.publishEvent(new JoinRequestApprovedEvent(teamId, requestId, leaderId, request.getAccountId()));
		return TeamMemberResult.of(member, accountReader.nameOf(member.getAccountId()));
	}

	/** 거절. 예외는 승인과 같다. */
	@Transactional
	public void reject(long leaderId, long teamId, long requestId) {
		JoinRequest request = decide(leaderId, teamId, requestId, JoinRequestStatus.REJECTED);
		eventPublisher.publishEvent(new JoinRequestRejectedEvent(teamId, requestId, leaderId, request.getAccountId()));
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
