package com.odo.notification.presentation.dto;

import java.time.Instant;
import java.util.Map;

import com.odo.notification.application.dto.NotificationResult;

/** 알림 목록의 항목. 11_Notification_spec.md의 "알림 목록". 실시간 연결의 data도 이 형식이다(PR B). */
public record NotificationResponse(
		long id,
		long seq,
		String type,
		Team team,
		Actor actor,
		Target target,
		Map<String, Object> detail,
		Instant createdAt) {

	public static NotificationResponse from(NotificationResult result) {
		return new NotificationResponse(result.id(), result.seq(), result.type(),
				new Team(result.teamId(), result.teamName()), new Actor(result.actorAccountId(), result.actorName()),
				new Target(result.targetType(), result.targetId(), result.targetName(), result.targetDeleted()),
				result.detail(), result.createdAt());
	}

	public record Team(
			long id,
			String name) {

	}

	public record Actor(
			long accountId,
			String name) {

	}

	public record Target(
			String type,
			long id,
			String name,
			boolean deleted) {

	}

}
