package com.odo.schedule.domain.event;

import java.util.List;

/**
 * 일정이 등록됐다. 등록과 같은 트랜잭션 안에서 동기로 발행한다.
 * affectedAccountIds는 이 일과 관련된 사람(제외되지 않은 필수와 선택 참여자)이다. 03_Schedule_spec.md의 "알림 이벤트 기록".
 */
public record ScheduleCreatedEvent(
		long teamId,
		long scheduleId,
		long actorAccountId,
		List<Long> affectedAccountIds) {

}
