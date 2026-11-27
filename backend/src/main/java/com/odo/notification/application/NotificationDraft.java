package com.odo.notification.application;

import java.util.Collection;
import java.util.Map;

/**
 * 알림 하나의 내용과 받는 사람. NotificationWriter가 받는 사람마다 한 행으로 쓴다.
 * type은 알림 종류(NotificationTypes), targetType은 가리키는 자료의 종류다.
 * actorAccountId는 그 일을 한 사람이고, 받는 사람에 있어도 빠진다.
 * payload에는 볼 때 읽을 수 없는 값만 담는다(바뀐 상태, 삭제 알림의 제목). 11_Notification_spec.md의 "보여 줄 값".
 */
public record NotificationDraft(
		long teamId,
		String type,
		String targetType,
		long targetId,
		long actorAccountId,
		Collection<Long> recipientAccountIds,
		Map<String, Object> payload) {

}
