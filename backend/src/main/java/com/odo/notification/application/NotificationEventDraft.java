package com.odo.notification.application;

import java.util.Map;

/**
 * 받는 사람을 빼고 한 알림 이벤트의 내용. 기록기가 받는 사람마다 한 행으로 쓴다.
 * type은 기능 이름을 앞에 붙인 사건의 종류(예: SCHEDULE_UPDATED), targetType은 가리키는 자료의 종류(예: SCHEDULE)다.
 * payload는 화면에 보여 줄 값의 복사본이다(예: 일정 제목).
 */
public record NotificationEventDraft(
		long teamId,
		String type,
		String targetType,
		long targetId,
		Map<String, Object> payload) {

}
