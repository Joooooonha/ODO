package com.odo.notification.application.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 화면에 보여 줄 알림 하나. 이름은 볼 때 읽은 지금 값이다(삭제 알림의 대상 이름만 지울 때의 제목).
 * targetName은 대상에 이름이 없거나(입장 요청) 대상이 지워졌으면 null이다. detail은 type마다 다르고 없으면 null이다.
 */
public record NotificationResult(
		long id,
		long seq,
		String type,
		long teamId,
		String teamName,
		long actorAccountId,
		String actorName,
		String targetType,
		long targetId,
		String targetName,
		boolean targetDeleted,
		Map<String, Object> detail,
		Instant createdAt) {

}
