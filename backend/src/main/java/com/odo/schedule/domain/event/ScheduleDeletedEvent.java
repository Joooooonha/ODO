package com.odo.schedule.domain.event;

import java.util.List;

/**
 * 일정이 지워졌다. 삭제와 같은 트랜잭션 안에서 동기로 발행한다.
 * title은 지울 때의 제목이다. 지운 뒤에는 일정을 다시 읽을 수 없다.
 * affectedAccountIds는 삭제 직전의 제외되지 않은 필수와 선택 참여자다.
 */
public record ScheduleDeletedEvent(
		long teamId,
		long scheduleId,
		String title,
		long actorAccountId,
		List<Long> affectedAccountIds) {

}
