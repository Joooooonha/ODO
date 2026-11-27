package com.odo.schedule.domain.event;

import com.odo.schedule.domain.entity.ParticipantStatus;

/**
 * 팀장이 한 사람의 참여 상태를 바꿨다. 같은 상태로 바꾸면 발행하지 않는다. 변경과 같은 트랜잭션 안에서 동기로 발행한다.
 */
public record ScheduleParticipantStatusChangedEvent(
		long teamId,
		long scheduleId,
		long actorAccountId,
		long accountId,
		ParticipantStatus status) {

}
