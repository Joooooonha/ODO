package com.odo.schedule.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.odo.schedule.domain.entity.Schedule;
import com.odo.schedule.domain.entity.ScheduleParticipant;
import com.odo.schedule.domain.repository.ScheduleParticipantRepository;
import com.odo.schedule.domain.repository.ScheduleRepository;
import com.odo.team.domain.event.TeamMemberJoinedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 새 팀원을 완료되지 않은 일정에 그 일정의 기본 상태로 넣는다. 투표 일정은 선택(미투표), 투표 없는 일정은 제외다.
 * 03_Schedule_spec.md의 "참여자 행과 새 팀원". 알림 이벤트는 남기지 않는다(팀장이 바꾼 것이 아니라 기본값이다).
 * 승인과 같은 트랜잭션에서 동기로 돈다. 여기서 실패하면 승인도 롤백되어 팀원인데 행이 없는 상태가 남지 않는다.
 * 승인은 이미 팀 행을 잠갔고, 일정 만들기도 같은 행을 잠그므로 둘이 차례로 실행된다.
 */
@Component
@RequiredArgsConstructor
public class NewMemberParticipantListener {

	private final ScheduleRepository scheduleRepository;

	private final ScheduleParticipantRepository participantRepository;

	private final Clock clock;

	@EventListener
	public void addToIncompleteSchedules(TeamMemberJoinedEvent event) {
		Instant now = clock.instant();
		// FOR SHARE로 읽는다. 그사이 팀장이 일정을 지우면 삭제가 이 잠금을 기다리므로 지워진 일정에 행을 넣지 않는다.
		List<Schedule> schedules = scheduleRepository.lockIncompleteForShare(event.teamId(), now);
		participantRepository.saveAll(schedules.stream()
			.map(schedule -> ScheduleParticipant.createParticipant(schedule.getId(), event.accountId(),
					schedule.defaultParticipantStatus(), now))
			.toList());
	}

}
