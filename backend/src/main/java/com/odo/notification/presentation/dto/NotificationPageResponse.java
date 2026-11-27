package com.odo.notification.presentation.dto;

import java.util.List;

import com.odo.notification.application.dto.NotificationPageResult;

/** 알림 목록 한 페이지. nextCursor는 다음 페이지가 없으면 null이다. */
public record NotificationPageResponse(
		List<NotificationResponse> items,
		Long nextCursor) {

	public static NotificationPageResponse from(NotificationPageResult result) {
		return new NotificationPageResponse(result.items().stream().map(NotificationResponse::from).toList(),
				result.nextCursor());
	}

}
