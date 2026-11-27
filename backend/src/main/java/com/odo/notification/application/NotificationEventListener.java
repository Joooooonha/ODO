package com.odo.notification.application;

import java.util.List;
import java.util.Map;

import com.odo.performance.domain.entity.PerformanceRole;
import com.odo.performance.domain.event.PerformanceDirectorDesignatedEvent;
import com.odo.performance.domain.event.PerformanceMembersEnrolledEvent;
import com.odo.schedule.domain.event.ScheduleCreatedEvent;
import com.odo.schedule.domain.event.ScheduleDeletedEvent;
import com.odo.schedule.domain.event.ScheduleParticipantStatusChangedEvent;
import com.odo.schedule.domain.event.ScheduleUpdatedEvent;
import com.odo.team.domain.event.JoinRequestApprovedEvent;
import com.odo.team.domain.event.JoinRequestRejectedEvent;
import com.odo.team.domain.event.JoinRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static com.odo.notification.application.NotificationTypes.PERFORMANCE_DIRECTOR_ASSIGNED;
import static com.odo.notification.application.NotificationTypes.PERFORMANCE_MEMBER_ADDED;
import static com.odo.notification.application.NotificationTypes.SCHEDULE_CREATED;
import static com.odo.notification.application.NotificationTypes.SCHEDULE_DELETED;
import static com.odo.notification.application.NotificationTypes.SCHEDULE_PARTICIPANT_STATUS_CHANGED;
import static com.odo.notification.application.NotificationTypes.SCHEDULE_UPDATED;
import static com.odo.notification.application.NotificationTypes.TARGET_JOIN_REQUEST;
import static com.odo.notification.application.NotificationTypes.TARGET_PERFORMANCE;
import static com.odo.notification.application.NotificationTypes.TARGET_SCHEDULE;
import static com.odo.notification.application.NotificationTypes.TARGET_TEAM;
import static com.odo.notification.application.NotificationTypes.TEAM_JOIN_APPROVED;
import static com.odo.notification.application.NotificationTypes.TEAM_JOIN_REJECTED;
import static com.odo.notification.application.NotificationTypes.TEAM_JOIN_REQUESTED;

/**
 * 기능의 사실 이벤트를 알림 초안으로 바꾼다. 11_Notification_spec.md의 "이벤트와 알림".
 * 발행한 트랜잭션 안에서 동기로 돈다(@EventListener). 받는 사람은 이벤트가 담아 온 관련된 사람이고,
 * 본인 제외와 저장은 NotificationWriter가 한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

	private final NotificationWriter writer;

	@EventListener
	public void on(ScheduleCreatedEvent event) {
		writer.add(new NotificationDraft(event.teamId(), SCHEDULE_CREATED, TARGET_SCHEDULE, event.scheduleId(),
				event.actorAccountId(), event.affectedAccountIds(), Map.of()));
	}

	@EventListener
	public void on(ScheduleUpdatedEvent event) {
		writer.add(new NotificationDraft(event.teamId(), SCHEDULE_UPDATED, TARGET_SCHEDULE, event.scheduleId(),
				event.actorAccountId(), event.affectedAccountIds(), Map.of()));
	}

	/** 삭제 알림은 대상이 늘 없으므로 지울 때의 제목을 복사해 둔다. */
	@EventListener
	public void on(ScheduleDeletedEvent event) {
		writer.add(new NotificationDraft(event.teamId(), SCHEDULE_DELETED, TARGET_SCHEDULE, event.scheduleId(),
				event.actorAccountId(), event.affectedAccountIds(), Map.of("title", event.title())));
	}

	/** 바뀐 상태는 사건의 내용이라 복사해 둔다. 볼 때 읽으면 그 뒤에 또 바뀐 지금 상태가 보인다. */
	@EventListener
	public void on(ScheduleParticipantStatusChangedEvent event) {
		writer.add(new NotificationDraft(event.teamId(), SCHEDULE_PARTICIPANT_STATUS_CHANGED, TARGET_SCHEDULE,
				event.scheduleId(), event.actorAccountId(), List.of(event.accountId()),
				Map.of("status", event.status().name())));
	}

	@EventListener
	public void on(JoinRequestedEvent event) {
		writer.add(new NotificationDraft(event.teamId(), TEAM_JOIN_REQUESTED, TARGET_JOIN_REQUEST, event.requestId(),
				event.requesterAccountId(), List.of(event.leaderAccountId()), Map.of()));
	}

	@EventListener
	public void on(JoinRequestApprovedEvent event) {
		writer.add(new NotificationDraft(event.teamId(), TEAM_JOIN_APPROVED, TARGET_TEAM, event.teamId(),
				event.leaderAccountId(), List.of(event.requesterAccountId()), Map.of()));
	}

	/** 요청한 사람은 팀원이 아니어서 팀으로 넘어갈 수 없으므로 대상은 입장 요청이다. */
	@EventListener
	public void on(JoinRequestRejectedEvent event) {
		writer.add(new NotificationDraft(event.teamId(), TEAM_JOIN_REJECTED, TARGET_JOIN_REQUEST, event.requestId(),
				event.leaderAccountId(), List.of(event.requesterAccountId()), Map.of()));
	}

	/** 넣은 사람 모두에게 명단 추가를, 디렉터로 넣은 사람에게는 디렉터 지정도 보낸다. 같은 사람은 명단 추가가 먼저다. */
	@EventListener
	public void on(PerformanceMembersEnrolledEvent event) {
		List<Long> enrolled = event.enrollments().stream().map(PerformanceMembersEnrolledEvent.Enrollment::accountId).toList();
		List<Long> directors = event.enrollments()
			.stream()
			.filter(enrollment -> enrollment.role() == PerformanceRole.DIRECTOR)
			.map(PerformanceMembersEnrolledEvent.Enrollment::accountId)
			.toList();
		writer.add(new NotificationDraft(event.teamId(), PERFORMANCE_MEMBER_ADDED, TARGET_PERFORMANCE,
				event.performanceId(), event.actorAccountId(), enrolled, Map.of()));
		writer.add(new NotificationDraft(event.teamId(), PERFORMANCE_DIRECTOR_ASSIGNED, TARGET_PERFORMANCE,
				event.performanceId(), event.actorAccountId(), directors, Map.of()));
	}

	@EventListener
	public void on(PerformanceDirectorDesignatedEvent event) {
		writer.add(new NotificationDraft(event.teamId(), PERFORMANCE_DIRECTOR_ASSIGNED, TARGET_PERFORMANCE,
				event.performanceId(), event.actorAccountId(), List.of(event.accountId()), Map.of()));
	}

}
