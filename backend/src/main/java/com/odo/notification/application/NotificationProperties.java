package com.odo.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * odo.notification.* 설정. 11_Notification_spec.md의 "설정".
 *
 * @param pageSize 목록 한 번에 가져오는 개수
 */
@ConfigurationProperties("odo.notification")
public record NotificationProperties(
		int pageSize) {

	public NotificationProperties {
		if (pageSize < 1) {
			throw new IllegalStateException("odo.notification.page-size는 1 이상이어야 한다");
		}
	}

}
