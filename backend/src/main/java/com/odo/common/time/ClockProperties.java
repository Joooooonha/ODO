package com.odo.common.time;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * odo.clock.* 설정. 03_Schedule_spec.md의 "서버 시계", "설정".
 *
 * @param referenceHost 시계 차이를 잴 기준 시간 서버(SNTP). 비어 있으면 감시를 끈다
 * @param checkInterval 재는 주기. 없으면 30초
 */
@ConfigurationProperties("odo.clock")
public record ClockProperties(
		String referenceHost,
		Duration checkInterval) {

	private static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(30);

	public ClockProperties {
		referenceHost = referenceHost == null || referenceHost.isBlank() ? null : referenceHost.strip();
		checkInterval = checkInterval == null ? DEFAULT_CHECK_INTERVAL : checkInterval;
	}

}
