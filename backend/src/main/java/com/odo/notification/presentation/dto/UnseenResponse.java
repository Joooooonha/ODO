package com.odo.notification.presentation.dto;

/** 새 알림 표시. 알림함을 열 때 본 순번보다 큰 알림이 남아 있으면 true다. */
public record UnseenResponse(
		boolean hasNew) {

}
