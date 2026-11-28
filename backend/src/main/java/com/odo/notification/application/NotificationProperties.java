package com.odo.notification.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * odo.notification.* 설정. 11_Notification_spec.md의 "설정".
 *
 * @param pageSize 목록 한 번에 가져오는 개수
 * @param pollInterval 감지기가 연결된 계정의 순번을 확인하는 주기. 새 알림이 보이기까지의 최대 지연이다
 * @param heartbeatInterval 이 시간 동안 보낸 것이 없는 연결에 빈 신호를 보낸다. 로드 밸런서가 연결을 끊지 않게 한다
 * @param reconnectRetry 연결을 열 때 화면에 알려 주는 다시 연결 간격(SSE retry)
 * @param catchUpLimit 한 연결에 한 번에 보낼 수 있는 알림 수. 넘으면 reset을 보내고 연결을 닫는다
 * @param detectorEnabled 감지기를 돌릴지. MockMvc 테스트는 끄고, 실시간 통합 테스트는 켠다
 */
@ConfigurationProperties("odo.notification")
public record NotificationProperties(
		int pageSize,
		Duration pollInterval,
		Duration heartbeatInterval,
		Duration reconnectRetry,
		int catchUpLimit,
		boolean detectorEnabled) {

	public NotificationProperties {
		if (pageSize < 1) {
			throw new IllegalStateException("odo.notification.page-size는 1 이상이어야 한다");
		}
		if (catchUpLimit < 1) {
			throw new IllegalStateException("odo.notification.catch-up-limit은 1 이상이어야 한다");
		}
		requirePositive("odo.notification.poll-interval", pollInterval);
		requirePositive("odo.notification.heartbeat-interval", heartbeatInterval);
		requirePositive("odo.notification.reconnect-retry", reconnectRetry);
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isNegative() || value.isZero()) {
			throw new IllegalStateException(name + "는 0보다 커야 한다");
		}
	}

}
