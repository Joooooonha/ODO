package com.odo.schedule.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.odo.account.application.AccountReader;
import com.odo.schedule.application.dto.ParticipantResult;
import com.odo.schedule.domain.entity.ParticipantStatus;
import com.odo.schedule.domain.entity.Schedule;
import com.odo.schedule.domain.entity.ScheduleParticipant;
import com.odo.schedule.domain.event.ScheduleParticipantStatusChangedEvent;
import com.odo.schedule.domain.exception.ParticipantNotFoundException;
import com.odo.schedule.domain.repository.ScheduleParticipantRepository;
import com.odo.team.application.TeamMemberReader;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 참여자 목록과 팀장의 참여자 상태 변경. 03_Schedule_spec.md의 "연습 일정의 참여자와 참여 투표"와 API 명세.
 * 투표 결과는 팀장이 바꾸지 않는다. 상태를 바꾸면 같은 트랜잭션 안에서 사실 이벤트를 발행하고, 알림 기능이 그 사람에게 알림을 남긴다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleParticipantService {

	private final ScheduleParticipantRepository participantRepository;

	private final ScheduleAccess scheduleAccess;

	private final AccountReader accountReader;

	private final TeamMemberReader teamMemberReader;

	private final ApplicationEventPublisher eventPublisher;

	private final Clock clock;

	/** 그 일정의 참여자 행 전부, 계정 ID 순. 없음, 팀원 아님은 ScheduleNotFoundException. */
	@Transactional(readOnly = true)
	public List<ParticipantResult> findParticipants(long accountId, long scheduleId) {
		Schedule schedule = scheduleAccess.requireVisibleSchedule(scheduleId, accountId);
		List<ScheduleParticipant> participants = participantRepository.findAllByScheduleIdOrderByAccountIdAsc(scheduleId);
		Map<Long, String> names = accountReader.namesOf(participants.stream().map(ScheduleParticipant::getAccountId).toList());
		Map<Long, Integer> generations = teamMemberReader.generationsOf(schedule.getTeamId());
		return participants.stream()
			.map(participant -> new ParticipantResult(participant.getAccountId(), names.get(participant.getAccountId()),
					generations.get(participant.getAccountId()), participant.getStatus(), participant.getVote()))
			.toList();
	}

	/**
	 * 팀장이 한 사람의 상태를 바꾼다. 일정 행을 잠그고 하므로 투표 처리와 겹치지 않는다.
	 * 선택에서 필수로 바꾸면 투표를 지우고, 필수에서 선택으로 바꾸면 미투표로 시작한다. 같은 상태면 아무것도 바꾸지 않는다.
	 * 없음, 팀원 아님 ScheduleNotFoundException, 팀장 아님 NotTeamLeaderException, 종료됨 ScheduleEndedException,
	 * 그 일정의 참여자가 아님 ParticipantNotFoundException, 그 일정에서 쓸 수 없는 상태 InvalidStatusException.
	 */
	@Transactional
	public ParticipantResult changeStatus(long accountId, long scheduleId, long targetAccountId,
			ParticipantStatus status) {
		Schedule schedule = scheduleAccess.lockScheduleAsLeader(scheduleId, accountId);
		Instant now = clock.instant();
		schedule.requireNotEnded(now);
		ScheduleParticipant participant = participantRepository.findByScheduleIdAndAccountId(scheduleId, targetAccountId)
			.orElseThrow(ParticipantNotFoundException::new);
		schedule.requireAllowedStatus(status);
		if (participant.changeStatus(status, now)) {
			eventPublisher.publishEvent(new ScheduleParticipantStatusChangedEvent(schedule.getTeamId(), scheduleId,
					accountId, targetAccountId, status));
		}
		return new ParticipantResult(targetAccountId, accountReader.nameOf(targetAccountId),
				teamMemberReader.generationOf(schedule.getTeamId(), targetAccountId), participant.getStatus(),
				participant.getVote());
	}

}
